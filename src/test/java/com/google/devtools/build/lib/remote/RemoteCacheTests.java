// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.remote;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.remote.util.Utils.getFromFuture;

import build.bazel.remote.execution.v2.Action;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Command;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.DirectoryNode;
import build.bazel.remote.execution.v2.FileNode;
import build.bazel.remote.execution.v2.RequestMetadata;
import build.bazel.remote.execution.v2.Tree;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.clock.JavaClock;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient.ActionKey;
import com.google.devtools.build.lib.remote.common.RemotePathResolver;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.common.options.Options;
import com.google.protobuf.ByteString;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.MockitoAnnotations;

/** Tests for {@link RemoteCache}. */
@RunWith(JUnit4.class)
public class RemoteCacheTests {

  private RemoteActionExecutionContext context;
  private RemotePathResolver remotePathResolver;
  private FileSystem fs;
  private Path execRoot;
  ArtifactRoot artifactRoot;
  private final DigestUtil digestUtil = new DigestUtil(DigestHashFunction.SHA256);
  private FakeActionInputFileCache fakeFileCache;

  private ListeningScheduledExecutorService retryService;

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);
    RequestMetadata metadata =
        TracingMetadataUtils.buildMetadata("none", "none", "action-id", null);
    context = RemoteActionExecutionContext.create(metadata);
    fs = new InMemoryFileSystem(new JavaClock(), DigestHashFunction.SHA256);
    execRoot = fs.getPath("/execroot/main");
    execRoot.createDirectoryAndParents();
    remotePathResolver = RemotePathResolver.createDefault(execRoot);
    fakeFileCache = new FakeActionInputFileCache(execRoot);
    artifactRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.Output, "outputs");
    artifactRoot.getRoot().asPath().createDirectoryAndParents();
    retryService = MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1));
  }

  @After
  public void afterEverything() throws InterruptedException {
    retryService.shutdownNow();
    retryService.awaitTermination(TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  @Test
  public void testDownloadEmptyBlobAndFile() throws Exception {
    // Test that downloading an empty BLOB/file does not try to perform a download.

    // arrange
    Path file = fs.getPath("/execroot/file");
    RemoteCache remoteCache = newRemoteCache();
    Digest emptyDigest = digestUtil.compute(new byte[0]);

    // act and assert
    assertThat(getFromFuture(remoteCache.downloadBlob(context, emptyDigest))).isEmpty();

    try (OutputStream out = file.getOutputStream()) {
      getFromFuture(remoteCache.downloadFile(context, file, emptyDigest));
    }
    assertThat(file.exists()).isTrue();
    assertThat(file.getFileSize()).isEqualTo(0);
  }

  @Test
  public void downloadOutErr_empty_doNotPerformDownload() throws Exception {
    // Test that downloading empty stdout/stderr does not try to perform a download.

    InMemoryRemoteCache remoteCache = newRemoteCache();
    Digest emptyDigest = digestUtil.compute(new byte[0]);
    ActionResult.Builder result = ActionResult.newBuilder();
    result.setStdoutDigest(emptyDigest);
    result.setStderrDigest(emptyDigest);

    RemoteCache.waitForBulkTransfer(
        remoteCache.downloadOutErr(
            context,
            result.build(),
            new FileOutErr(execRoot.getRelative("stdout"), execRoot.getRelative("stderr"))),
        true);

    assertThat(remoteCache.getNumSuccessfulDownloads()).isEqualTo(0);
    assertThat(remoteCache.getNumFailedDownloads()).isEqualTo(0);
  }

  @Test
  public void testDownloadFileWithSymlinkTemplate() throws Exception {
    // Test that when a symlink template is provided, we don't actually download files to disk.
    // Instead, a symbolic link should be created that points to a location where the file may
    // actually be found. That location could, for example, be backed by a FUSE file system that
    // exposes the Content Addressable Storage.

    // arrange
    final ConcurrentMap<Digest, byte[]> cas = new ConcurrentHashMap<>();

    Digest helloDigest = digestUtil.computeAsUtf8("hello-contents");
    cas.put(helloDigest, "hello-contents".getBytes(StandardCharsets.UTF_8));

    Path file = fs.getPath("/execroot/symlink-to-file");
    RemoteOptions options = Options.getDefaults(RemoteOptions.class);
    options.remoteDownloadSymlinkTemplate = "/home/alice/cas/{hash}-{size_bytes}";
    RemoteCache remoteCache = new InMemoryRemoteCache(cas, options, digestUtil);

    // act
    getFromFuture(remoteCache.downloadFile(context, file, helloDigest));

    // assert
    assertThat(file.isSymbolicLink()).isTrue();
    assertThat(file.readSymbolicLink())
        .isEqualTo(
            PathFragment.create(
                "/home/alice/cas/a378b939ad2e1d470a9a28b34b0e256b189e85cb236766edc1d46ec3b6ca82e5-14"));
  }

  @Test
  public void testUploadDirectory() throws Exception {
    // Test that uploading a directory works.

    // arrange
    Digest fooDigest = fakeFileCache.createScratchInput(ActionInputHelper.fromPath("a/foo"), "xyz");
    Digest quxDigest =
        fakeFileCache.createScratchInput(ActionInputHelper.fromPath("bar/qux"), "abc");
    Digest barDigest =
        fakeFileCache.createScratchInputDirectory(
            ActionInputHelper.fromPath("bar"),
            Tree.newBuilder()
                .setRoot(
                    Directory.newBuilder()
                        .addFiles(
                            FileNode.newBuilder()
                                .setIsExecutable(true)
                                .setName("qux")
                                .setDigest(quxDigest)
                                .build())
                        .build())
                .build());
    Path fooFile = execRoot.getRelative("a/foo");
    Path quxFile = execRoot.getRelative("bar/qux");
    quxFile.setExecutable(true);
    Path barDir = execRoot.getRelative("bar");
    Command cmd = Command.newBuilder().addOutputFiles("bla").build();
    Digest cmdDigest = digestUtil.compute(cmd);
    Action action = Action.newBuilder().setCommandDigest(cmdDigest).build();
    Digest actionDigest = digestUtil.compute(action);

    // act
    InMemoryRemoteCache remoteCache = newRemoteCache();
    ActionResult result =
        remoteCache.upload(
            context,
            remotePathResolver,
            digestUtil.asActionKey(actionDigest),
            action,
            cmd,
            ImmutableList.of(fooFile, barDir),
            new FileOutErr(execRoot.getRelative("stdout"), execRoot.getRelative("stderr")));

    // assert
    ActionResult.Builder expectedResult = ActionResult.newBuilder();
    expectedResult.addOutputFilesBuilder().setPath("a/foo").setDigest(fooDigest);
    expectedResult.addOutputDirectoriesBuilder().setPath("bar").setTreeDigest(barDigest);
    assertThat(result).isEqualTo(expectedResult.build());

    ImmutableList<Digest> toQuery =
        ImmutableList.of(fooDigest, quxDigest, barDigest, cmdDigest, actionDigest);
    assertThat(getFromFuture(remoteCache.findMissingDigests(context, toQuery))).isEmpty();
  }

  @Test
  public void upload_emptyBlobAndFile_doNotPerformUpload() throws Exception {
    // Test that uploading an empty BLOB/file does not try to perform an upload.
    InMemoryRemoteCache remoteCache = newRemoteCache();
    Digest emptyDigest = fakeFileCache.createScratchInput(ActionInputHelper.fromPath("file"), "");
    Path file = execRoot.getRelative("file");

    getFromFuture(remoteCache.uploadBlob(context, emptyDigest, ByteString.EMPTY));
    assertThat(getFromFuture(remoteCache.findMissingDigests(context, ImmutableSet.of(emptyDigest))))
        .containsExactly(emptyDigest);

    getFromFuture(remoteCache.uploadFile(context, emptyDigest, file));
    assertThat(getFromFuture(remoteCache.findMissingDigests(context, ImmutableSet.of(emptyDigest))))
        .containsExactly(emptyDigest);
  }

  @Test
  public void upload_emptyOutputs_doNotPerformUpload() throws Exception {
    // Test that uploading an empty output does not try to perform an upload.

    // arrange
    Digest emptyDigest =
        fakeFileCache.createScratchInput(ActionInputHelper.fromPath("bar/test/wobble"), "");
    Path file = execRoot.getRelative("bar/test/wobble");
    InMemoryRemoteCache remoteCache = newRemoteCache();
    Action action = Action.getDefaultInstance();
    ActionKey actionDigest = digestUtil.computeActionKey(action);
    Command cmd = Command.getDefaultInstance();

    // act
    remoteCache.upload(
        context,
        remotePathResolver,
        actionDigest,
        action,
        cmd,
        ImmutableList.of(file),
        new FileOutErr(execRoot.getRelative("stdout"), execRoot.getRelative("stderr")));

    // assert
    assertThat(getFromFuture(remoteCache.findMissingDigests(context, ImmutableSet.of(emptyDigest))))
        .containsExactly(emptyDigest);
  }

  @Test
  public void testUploadEmptyDirectory() throws Exception {
    // Test that uploading an empty directory works.

    // arrange
    final Digest barDigest =
        fakeFileCache.createScratchInputDirectory(
            ActionInputHelper.fromPath("bar"),
            Tree.newBuilder().setRoot(Directory.newBuilder().build()).build());
    final Path barDir = execRoot.getRelative("bar");
    Action action = Action.getDefaultInstance();
    ActionKey actionDigest = digestUtil.computeActionKey(action);
    Command cmd = Command.getDefaultInstance();

    // act
    InMemoryRemoteCache remoteCache = newRemoteCache();
    ActionResult result =
        remoteCache.upload(
            context,
            remotePathResolver,
            actionDigest,
            action,
            cmd,
            ImmutableList.of(barDir),
            new FileOutErr(execRoot.getRelative("stdout"), execRoot.getRelative("stderr")));

    // assert
    ActionResult.Builder expectedResult = ActionResult.newBuilder();
    expectedResult.addOutputDirectoriesBuilder().setPath("bar").setTreeDigest(barDigest);
    assertThat(result).isEqualTo(expectedResult.build());
    assertThat(getFromFuture(remoteCache.findMissingDigests(context, ImmutableList.of(barDigest))))
        .isEmpty();
  }

  @Test
  public void testUploadNestedDirectory() throws Exception {
    // Test that uploading a nested directory works.

    // arrange
    final Digest wobbleDigest =
        fakeFileCache.createScratchInput(ActionInputHelper.fromPath("bar/test/wobble"), "xyz");
    final Digest quxDigest =
        fakeFileCache.createScratchInput(ActionInputHelper.fromPath("bar/qux"), "abc");
    final Directory testDirMessage =
        Directory.newBuilder()
            .addFiles(FileNode.newBuilder().setName("wobble").setDigest(wobbleDigest).build())
            .build();
    final Digest testDigest = digestUtil.compute(testDirMessage);
    final Tree barTree =
        Tree.newBuilder()
            .setRoot(
                Directory.newBuilder()
                    .addFiles(
                        FileNode.newBuilder()
                            .setIsExecutable(true)
                            .setName("qux")
                            .setDigest(quxDigest))
                    .addDirectories(
                        DirectoryNode.newBuilder().setName("test").setDigest(testDigest)))
            .addChildren(testDirMessage)
            .build();
    final Digest barDigest =
        fakeFileCache.createScratchInputDirectory(ActionInputHelper.fromPath("bar"), barTree);

    final Path quxFile = execRoot.getRelative("bar/qux");
    quxFile.setExecutable(true);
    final Path barDir = execRoot.getRelative("bar");

    Action action = Action.getDefaultInstance();
    ActionKey actionDigest = digestUtil.computeActionKey(action);
    Command cmd = Command.getDefaultInstance();

    // act
    InMemoryRemoteCache remoteCache = newRemoteCache();
    ActionResult result =
        remoteCache.upload(
            context,
            remotePathResolver,
            actionDigest,
            action,
            cmd,
            ImmutableList.of(barDir),
            new FileOutErr(execRoot.getRelative("stdout"), execRoot.getRelative("stderr")));

    // assert
    ActionResult.Builder expectedResult = ActionResult.newBuilder();
    expectedResult.addOutputDirectoriesBuilder().setPath("bar").setTreeDigest(barDigest);
    assertThat(result).isEqualTo(expectedResult.build());

    ImmutableList<Digest> toQuery = ImmutableList.of(wobbleDigest, quxDigest, barDigest);
    assertThat(getFromFuture(remoteCache.findMissingDigests(context, toQuery))).isEmpty();
  }

  private InMemoryRemoteCache newRemoteCache() {
    RemoteOptions options = Options.getDefaults(RemoteOptions.class);
    return new InMemoryRemoteCache(options, digestUtil);
  }
}
