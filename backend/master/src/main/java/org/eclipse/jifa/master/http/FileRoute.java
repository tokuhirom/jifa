/********************************************************************************
 * Copyright (c) 2020 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.jifa.master.http;

import io.reactivex.Single;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.reactivex.core.MultiMap;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.core.buffer.Buffer;
import io.vertx.reactivex.core.http.HttpServerRequest;
import io.vertx.reactivex.ext.web.FileUpload;
import io.vertx.reactivex.ext.web.Router;
import io.vertx.reactivex.ext.web.RoutingContext;
import io.vertx.reactivex.ext.web.client.HttpRequest;
import io.vertx.reactivex.ext.web.codec.BodyCodec;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jifa.common.ErrorCode;
import org.eclipse.jifa.common.JifaException;
import org.eclipse.jifa.common.enums.FileTransferState;
import org.eclipse.jifa.common.enums.FileType;
import org.eclipse.jifa.common.enums.ProgressState;
import org.eclipse.jifa.common.util.FileUtil;
import org.eclipse.jifa.common.util.HTTPRespGuarder;
import org.eclipse.jifa.common.vo.FileInfo;
import org.eclipse.jifa.common.vo.PageView;
import org.eclipse.jifa.common.vo.TransferProgress;
import org.eclipse.jifa.common.vo.TransferringFile;
import org.eclipse.jifa.master.Constant;
import org.eclipse.jifa.master.entity.File;
import org.eclipse.jifa.master.entity.enums.Deleter;
import org.eclipse.jifa.master.entity.enums.JobType;
import org.eclipse.jifa.master.model.TransferWay;
import org.eclipse.jifa.master.model.User;
import org.eclipse.jifa.master.service.ProxyDictionary;
import org.eclipse.jifa.master.service.reactivex.FileService;
import org.eclipse.jifa.master.service.reactivex.JobService;
import org.eclipse.jifa.master.support.WorkerClient;
import org.eclipse.jifa.master.vo.ExtendedFileInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.eclipse.jifa.common.util.Assertion.ASSERT;
import static org.eclipse.jifa.common.util.GsonHolder.GSON;

class FileRoute extends BaseRoute implements Constant {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileRoute.class.getName());

    private static String PUB_KEY = Constant.EMPTY_STRING;

    static {
        String path = System.getProperty("user.home") + java.io.File.separator + ".ssh" + java.io.File.separator +
                      "jifa-ssh-key.pub";
        java.io.File file = new java.io.File(path);
        if (file.exists()) {
            PUB_KEY = FileUtil.content(file);
        } else {
            LOGGER.warn("SSH public key file {} doesn't exist", file.getAbsolutePath());
        }
    }

    private FileService fileService;

    private JobService jobService;

    private static ExtendedFileInfo buildFileInfo(File file) {
        ExtendedFileInfo info = new ExtendedFileInfo();
        info.setOriginalName(file.getOriginalName());
        info.setDisplayName(
            StringUtils.isBlank(file.getDisplayName()) ? file.getOriginalName() : file.getDisplayName());
        info.setName(file.getName());
        info.setType(file.getType());
        info.setSize(file.getSize());
        info.setTransferState(file.getTransferState());
        info.setShared(file.isShared());
        info.setDownloadable(false);
        info.setCreationTime(file.getCreationTime());
        info.setUserId(file.getUserId());
        return info;
    }

    void init(Vertx vertx, JsonObject config, Router apiRouter) {
        fileService = ProxyDictionary.lookup(FileService.class);
        jobService = ProxyDictionary.lookup(JobService.class);

        apiRouter.get().path(FILES).handler(this::files);
        apiRouter.get().path(FILE).handler(this::file);
        apiRouter.post().path(FILE_DELETE).handler(this::delete);
        apiRouter.post().path(TRANSFER_BY_URL).handler(context -> transfer(context, TransferWay.URL));
        apiRouter.post().path(TRANSFER_BY_SCP).handler(context -> transfer(context, TransferWay.SCP));
        apiRouter.post().path(TRANSFER_BY_OSS).handler(context -> transfer(context, TransferWay.OSS));
        apiRouter.post().path(TRANSFER_BY_S3).handler(context -> transfer(context, TransferWay.S3));

        apiRouter.get().path(TRANSFER_PROGRESS).handler(this::fileTransportProgress);
        apiRouter.get().path(PUBLIC_KEY).handler(this::publicKey);

        apiRouter.post().path(FILE_SET_SHARED).handler(this::setShared);
        apiRouter.post().path(FILE_UNSET_SHARED).handler(this::unsetShared);

        apiRouter.post().path(FILE_UPDATE_DISPLAY_NAME).handler(this::updateDisplayName);

        apiRouter.post().path(UPLOAD_TO_OSS).handler(this::uploadToOSS);
        apiRouter.get().path(UPLOAD_TO_OSS_PROGRESS).handler(this::uploadToOSSProgress);

        apiRouter.get().path(DOWNLOAD).handler(this::download);

        apiRouter.post().path(FILE_UPLOAD).handler(this::upload);
    }

    private void files(RoutingContext context) {
        String userId = context.<User>get(USER_INFO_KEY).getId();
        int page = Integer.parseInt(context.request().getParam(PAGE));
        int pageSize = Integer.parseInt(context.request().getParam(PAGE_SIZE));
        FileType type = FileType.valueOf(context.request().getParam(FILE_TYPE));
        String expected = context.request().getParam("expectedFilename");

        Single<Integer> countSingle = fileService.rxCount(userId, type, expected);
        Single<List<File>> fileRecordSingle = fileService.rxFiles(userId, type, expected, page, pageSize);
        Single.zip(countSingle, fileRecordSingle, (count, fileRecords) -> {
            PageView<FileInfo> pv = new PageView<>();
            pv.setTotalSize(count);
            pv.setPage(page);
            pv.setPageSize(pageSize);
            pv.setData(fileRecords.stream().map(FileRoute::buildFileInfo).collect(Collectors.toList()));
            return pv;
        }).subscribe(pageView -> HTTPRespGuarder.ok(context, pageView), t -> HTTPRespGuarder.fail(context, t));
    }

    private void file(RoutingContext context) {
        User user = context.get(USER_INFO_KEY);
        String name = context.request().getParam("name");
        fileService.rxFile(name)
                   .doOnSuccess(this::assertFileAvailable)
                   .doOnSuccess(file -> checkPermission(user, file))
                   .map(FileRoute::buildFileInfo)
                   .subscribe(fileView -> HTTPRespGuarder.ok(context, fileView),
                              throwable -> HTTPRespGuarder.fail(context, throwable));
    }

    private void delete(RoutingContext context) {
        User user = context.get(USER_INFO_KEY);
        String name = context.request().getParam("name");

        fileService.rxFile(name)
                   .doOnSuccess(this::assertFileAvailable)
                   .doOnSuccess(file -> checkDeletePermission(user, file))
                   .doOnSuccess(file -> ASSERT.isTrue(file.getTransferState().isFinal()))
                   .flatMapCompletable(
                       file -> fileService.rxDeleteFile(name,
                                                        file.getUserId().equals(user.getId()) ?
                                                        Deleter.USER : Deleter.ADMIN))
                   .subscribe(() -> HTTPRespGuarder.ok(context),
                              t -> HTTPRespGuarder.fail(context, t));
    }

    private void transfer(RoutingContext context, TransferWay way) {
        String userId = context.<User>get(USER_INFO_KEY).getId();
        HttpServerRequest request = context.request();
        String[] paths = way.getPathKeys();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paths.length; i++) {
            sb.append(request.getParam(paths[i]));
            if (i != paths.length - 1) {
                sb.append("_");

            }
        }
        String origin = extractOriginalName(sb.toString());
        FileType type = FileType.valueOf(context.request().getParam("type"));

        String name = buildFileName(userId, origin);
        request.params().add("fileName", name);

        fileService.rxTransfer(userId, type, origin, name, way, convert(request.params()))
                   .ignoreElement()
                   .toSingleDefault(name)
                   .subscribe(n -> HTTPRespGuarder.ok(context, new TransferringFile(n)),
                              t -> HTTPRespGuarder.fail(context, t));
    }

    private void fileTransportProgress(RoutingContext context) {
        String name = context.request().getParam("name");
        User user = context.get(USER_INFO_KEY);

        jobService.rxFindActive(JobType.FILE_TRANSFER, name).flatMap(job -> {
            if (job.notFound()) {
                return fileService.rxFile(name)
                                  .doOnSuccess(this::assertFileAvailable)
                                  .doOnSuccess(file -> checkPermission(user, file))
                                  .flatMap(file -> Single.just(toProgress(file)));
            }

            checkPermission(user, job);
            Single<TransferProgress> progressSingle =
                WorkerClient.send(context.request(), job.getHostIP())
                            .doOnSuccess(resp -> ASSERT.isTrue(HTTP_GET_OK_STATUS_CODE == resp.statusCode(),
                                                               resp::bodyAsString))
                            .map(resp -> GSON.fromJson(resp.bodyAsString(), TransferProgress.class));

            return progressSingle.flatMap(progress -> {
                ProgressState state = progress.getState();
                if (state.isFinal()) {
                    LOGGER.info("File transfer {} done, state is {}", name, progress.getState());
                    FileTransferState transferState = FileTransferState.fromProgressState(state);
                    return fileService.rxTransferDone(name, transferState, progress.getTotalSize())
                                      .andThen(Single.just(progress));
                }
                return Single.just(progress);
            });
        }).subscribe(p -> HTTPRespGuarder.ok(context, p),
                     t -> HTTPRespGuarder.fail(context, t));
    }

    private void uploadToOSS(RoutingContext context) {
        String name = context.request().getParam("srcName");
        ASSERT.isTrue(StringUtils.isNotBlank(name), ErrorCode.ILLEGAL_ARGUMENT, "srcName mustn't be empty");
        User user = context.get(USER_INFO_KEY);
        fileService.rxFile(name)
                   .doOnSuccess(file -> assertFileAvailable(file))
                   .doOnSuccess(file -> checkPermission(user, file))
                   .doOnSuccess(file -> ASSERT.isTrue(file.transferred(), ErrorCode.NOT_TRANSFERRED))
                   .doOnSuccess(file -> context.request().params().add("type", file.getType().name()))
                   .flatMap(file -> WorkerClient.send(context.request(), file.getHostIP()))
                   .subscribe(resp -> HTTPRespGuarder.ok(context, resp.statusCode(), resp.bodyAsString()),
                              t -> HTTPRespGuarder.fail(context, t));
    }

    private void uploadToOSSProgress(RoutingContext context) {
        String name = context.request().getParam("name");
        ASSERT.isTrue(StringUtils.isNotBlank(name), ErrorCode.ILLEGAL_ARGUMENT, "name mustn't be empty");
        User user = context.get(USER_INFO_KEY);
        fileService.rxFile(name)
                   .doOnSuccess(file -> assertFileAvailable(file))
                   .doOnSuccess(file -> checkPermission(user, file))
                   .doOnSuccess(file -> ASSERT.isTrue(file.transferred(), ErrorCode.NOT_TRANSFERRED))
                   .flatMap(file -> WorkerClient.send(context.request(), file.getHostIP()))
                   .subscribe(resp -> HTTPRespGuarder.ok(context, resp.statusCode(), resp.bodyAsString()),
                              t -> HTTPRespGuarder.fail(context, t));
    }

    private void setShared(RoutingContext context) {
        String name = context.request().getParam("name");
        User user = context.get(USER_INFO_KEY);
        fileService.rxFile(name)
                   .doOnSuccess(file -> ASSERT.isTrue(file.found(), ErrorCode.FILE_DOES_NOT_EXIST))
                   .doOnSuccess(file -> checkPermission(user, file))
                   .ignoreElement()
                   .andThen(fileService.rxSetShared(name))
                   .subscribe(() -> HTTPRespGuarder.ok(context),
                              t -> HTTPRespGuarder.fail(context, t));
    }

    private void updateDisplayName(RoutingContext context) {
        String name = context.request().getParam("name");
        String displayName = context.request().getParam("displayName");
        User user = context.get(USER_INFO_KEY);
        fileService.rxFile(name)
                   .doOnSuccess(file -> ASSERT.isTrue(file.found(), ErrorCode.FILE_DOES_NOT_EXIST))
                   .doOnSuccess(file -> checkPermission(user, file))
                   .ignoreElement()
                   .andThen(fileService.rxUpdateDisplayName(name, displayName))
                   .subscribe(() -> HTTPRespGuarder.ok(context),
                              t -> HTTPRespGuarder.fail(context, t));
    }

    private void publicKey(RoutingContext context) {
        HTTPRespGuarder.ok(context, PUB_KEY);
    }

    private void unsetShared(RoutingContext context) {
        HTTPRespGuarder.fail(context, new JifaException(ErrorCode.UNSUPPORTED_OPERATION));
    }

    private String extractOriginalName(String path) {
        String name = path.substring(path.lastIndexOf(java.io.File.separatorChar) + 1);

        if (name.contains("?")) {
            name = name.substring(0, name.indexOf("?"));
        }

        name = name.replaceAll("[%\\\\& ]", "_");

        if (name.length() == 0) {
            name = System.currentTimeMillis() + "";
        }

        return name;
    }

    private Map<String, String> convert(MultiMap src) {
        Map<String, String> target = new HashMap<>();
        for (Map.Entry<String, String> entry : src) {
            target.put(entry.getKey(), entry.getValue());
        }
        return target;
    }

    private TransferProgress toProgress(File file) {
        FileTransferState transferState = file.getTransferState();
        ASSERT.isTrue(transferState.isFinal(), ErrorCode.SANITY_CHECK);
        TransferProgress progress = new TransferProgress();
        progress.setState(transferState.toProgressState());
        progress.setTotalSize(file.getSize());
        if (transferState == FileTransferState.SUCCESS) {
            progress.setPercent(1.0);
            progress.setTransferredSize(file.getSize());
        }
        return progress;
    }

    private void download(RoutingContext context) {
        String name = context.request().getParam("name");
        User user = context.get(USER_INFO_KEY);
        fileService.rxFile(name)
                .doOnSuccess(file -> ASSERT.isTrue(file.found(), ErrorCode.FILE_DOES_NOT_EXIST))
                .doOnSuccess(file -> checkPermission(user, file))
                .doOnSuccess(file -> ASSERT.isTrue(file.getSize() < MAX_SIZE_FOR_DOWNLOAD, ErrorCode.FILE_TOO_BIG))
                .flatMap(file -> {
                    context.response()
                            .putHeader(HEADER_CONTENT_LENGTH_KEY, String.valueOf(file.getSize()))
                            .putHeader(HEADER_CONTENT_DISPOSITION, "attachment;filename=" + file.getName())
                            .putHeader(HEADER_CONTENT_TYPE_KEY, CONTENT_TYPE_FILE_FORM);
                    HttpRequest<Buffer> workerRequest = WorkerClient.request(context.request().method(), file.getHostIP(), context.request().uri());
                    workerRequest.as(BodyCodec.pipe(context.response()));
                    return WorkerClient.send(workerRequest, context.request().method() == HttpMethod.POST, null);
                })
                .subscribe(resp -> context.response().end(),
                        t -> HTTPRespGuarder.fail(context, t));

    }

    private void upload(RoutingContext context) {
        FileUpload[] fileUploads = context.fileUploads().toArray(new FileUpload[0]);
        if (fileUploads.length == 0) {
            HTTPRespGuarder.ok(context);
            return;
        }
        FileUpload file = fileUploads[0];
        String userId = context.<User>get(USER_INFO_KEY).getId();
        HttpServerRequest request = context.request();
        String origin = file.fileName();
        FileType type = FileType.valueOf(context.request().getParam("type"));

        String name = buildFileName(userId, origin);
        request.params().add("fileName", name);

        fileService.rxTransfer(userId, type, origin, name, TransferWay.UPLOAD, convert(request.params()))
                   .flatMap(job -> WorkerClient.uploadFile(job.getHostIP(),
                                                           new java.io.File(file.uploadedFileName()),
                                                           name,
                                                           type))
                   .subscribe(resp -> {
                                  FileTransferState state = resp.statusCode() == Constant.HTTP_POST_CREATED_STATUS ?
                                                            FileTransferState.SUCCESS : FileTransferState.ERROR;
                                  fileService.rxTransferDone(name, state, file.size())
                                             .subscribe(
                                                 () -> HTTPRespGuarder
                                                     .ok(context, resp.statusCode(), new TransferringFile(name)),
                                                 t -> HTTPRespGuarder.fail(context, t)
                                             );
                                  context.vertx().executeBlocking(
                                      p -> {
                                          for (FileUpload f : fileUploads) {
                                              context.vertx().fileSystem().delete(f.uploadedFileName());
                                          }
                                      }
                                  );
                              },
                              t -> HTTPRespGuarder.fail(context, t));
    }
}
