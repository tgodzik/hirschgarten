package org.jetbrains.bsp.bazel.server.bsp.managers;

import ch.epfl.scala.bsp4j.BuildClient;
import ch.epfl.scala.bsp4j.TextDocumentIdentifier;
import io.vavr.collection.List;
import io.vavr.collection.Seq;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.jetbrains.bsp.bazel.bazelrunner.BazelRunner;
import org.jetbrains.bsp.bazel.server.bep.BepServer;
import org.jetbrains.bsp.bazel.workspacecontext.TargetsSpec;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;

public class BazelBspCompilationManager {

  private final BazelRunner bazelRunner;
  private BuildClient client;
  private Path workspaceRoot;
  private Map<String, Set<TextDocumentIdentifier>> hasAnyProblems;

  public BazelBspCompilationManager(
      BazelRunner bazelRunner, Map<String, Set<TextDocumentIdentifier>> hasAnyProblems) {
    this.bazelRunner = bazelRunner;
    this.hasAnyProblems = hasAnyProblems;
  }

  public BepBuildResult buildTargetsWithBep(
      CancelChecker cancelChecker, TargetsSpec targetSpecs, String originId) {
    return buildTargetsWithBep(cancelChecker, targetSpecs, List.empty(), originId);
  }

  public BepBuildResult buildTargetsWithBep(
          CancelChecker cancelChecker,
          TargetsSpec targetSpecs,
          Seq<String> extraFlags,
          String originId) {
    var bepServer = BepServer.newBepServer(client, workspaceRoot, hasAnyProblems, Optional.ofNullable(originId));
    var bepReader = new BepReader(bepServer);
    try {
      bepReader.start();
      var result =
              bazelRunner
                      .commandBuilder()
                      .build()
                      .withFlags(extraFlags.asJava())
                      .withTargets(targetSpecs)
                      .executeBazelBesCommand(originId, bepReader.getEventFile().toPath().toAbsolutePath())
                      .waitAndGetResult(cancelChecker, true);
      bepReader.finishBuild();
      bepReader.await();
      return new BepBuildResult(result, bepServer.getBepOutput());
    } catch (ExecutionException | InterruptedException e) {
      throw new RuntimeException(e);
    } finally {
      bepReader.finishBuild();
    }
  }

  public void setClient(BuildClient client) {
    this.client = client;
  }

  public BuildClient getClient() {
    return client;
  }

  public void setWorkspaceRoot(Path workspaceRoot) {
    this.workspaceRoot = workspaceRoot;
  }

  public Path getWorkspaceRoot() {
    return workspaceRoot;
  }
}
