/*
 * Copyright 2016 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.android.sync.projectstructure;

import com.android.builder.model.SourceProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.idea.blaze.android.resources.LightResourceClassService;
import com.google.idea.blaze.android.run.BlazeAndroidRunConfigurationHandler;
import com.google.idea.blaze.android.sync.model.AndroidResourceModule;
import com.google.idea.blaze.android.sync.model.AndroidSdkPlatform;
import com.google.idea.blaze.android.sync.model.BlazeAndroidSyncData;
import com.google.idea.blaze.android.sync.model.idea.BlazeAndroidModel;
import com.google.idea.blaze.android.sync.model.idea.SourceProviderImpl;
import com.google.idea.blaze.android.sync.sdk.SdkUtil;
import com.google.idea.blaze.base.ideinfo.AndroidIdeInfo;
import com.google.idea.blaze.base.ideinfo.ArtifactLocation;
import com.google.idea.blaze.base.ideinfo.TargetIdeInfo;
import com.google.idea.blaze.base.ideinfo.TargetKey;
import com.google.idea.blaze.base.model.BlazeProjectData;
import com.google.idea.blaze.base.model.primitives.Kind;
import com.google.idea.blaze.base.model.primitives.Label;
import com.google.idea.blaze.base.model.primitives.TargetExpression;
import com.google.idea.blaze.base.model.primitives.WorkspaceRoot;
import com.google.idea.blaze.base.projectview.ProjectViewSet;
import com.google.idea.blaze.base.projectview.section.sections.TargetSection;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.base.scope.output.PrintOutput;
import com.google.idea.blaze.base.sync.BlazeSyncPlugin;
import com.google.idea.blaze.base.sync.data.BlazeProjectDataManager;
import com.google.idea.blaze.base.sync.workspace.ArtifactLocationDecoder;
import com.intellij.execution.RunManager;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.StdModuleTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModifiableRootModel;
import java.io.File;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.jetbrains.android.facet.AndroidFacet;

/** Updates the IDE's project structure. */
public class BlazeAndroidProjectStructureSyncer {

  public static void updateProjectStructure(
      Project project,
      BlazeContext context,
      WorkspaceRoot workspaceRoot,
      ProjectViewSet projectViewSet,
      BlazeProjectData blazeProjectData,
      BlazeSyncPlugin.ModuleEditor moduleEditor,
      Module workspaceModule,
      ModifiableRootModel workspaceModifiableModel,
      boolean isAndroidWorkspace) {
    LightResourceClassService.Builder rClassBuilder =
        new LightResourceClassService.Builder(project);

    if (isAndroidWorkspace) {
      BlazeAndroidSyncData syncData = blazeProjectData.syncState.get(BlazeAndroidSyncData.class);
      if (syncData == null) {
        return;
      }

      AndroidSdkPlatform androidSdkPlatform = syncData.androidSdkPlatform;
      if (androidSdkPlatform != null) {
        int totalOrderEntries = 0;

        // Create the workspace module
        updateWorkspaceModule(project, workspaceRoot, workspaceModule, androidSdkPlatform);

        // Create android resource modules
        // Because we're setting up dependencies, the modules have to exist before we configure them
        Map<TargetKey, AndroidResourceModule> targetToAndroidResourceModule = Maps.newHashMap();
        for (AndroidResourceModule androidResourceModule :
            syncData.importResult.androidResourceModules) {
          targetToAndroidResourceModule.put(androidResourceModule.targetKey, androidResourceModule);
          String moduleName = moduleNameForAndroidModule(androidResourceModule.targetKey);
          moduleEditor.createModule(moduleName, StdModuleTypes.JAVA);
        }

        // Configure android resource modules
        for (AndroidResourceModule androidResourceModule : targetToAndroidResourceModule.values()) {
          TargetIdeInfo target = blazeProjectData.targetMap.get(androidResourceModule.targetKey);
          AndroidIdeInfo androidIdeInfo = target.androidIdeInfo;
          assert androidIdeInfo != null;

          String moduleName = moduleNameForAndroidModule(target.key);
          Module module = moduleEditor.findModule(moduleName);
          assert module != null;
          ModifiableRootModel modifiableRootModel = moduleEditor.editModule(module);

          updateAndroidTargetModule(
              project,
              workspaceRoot,
              blazeProjectData.artifactLocationDecoder,
              androidSdkPlatform,
              target,
              module,
              modifiableRootModel,
              androidResourceModule);

          for (TargetKey resourceDependency :
              androidResourceModule.transitiveResourceDependencies) {
            if (!targetToAndroidResourceModule.containsKey(resourceDependency)) {
              continue;
            }
            String dependencyModuleName = moduleNameForAndroidModule(resourceDependency);
            Module dependency = moduleEditor.findModule(dependencyModuleName);
            if (dependency == null) {
              continue;
            }
            modifiableRootModel.addModuleOrderEntry(dependency);
            ++totalOrderEntries;
          }
          rClassBuilder.addRClass(androidIdeInfo.resourceJavaPackage, module);
          // Add a dependency from the workspace to the resource module
          workspaceModifiableModel.addModuleOrderEntry(module);
        }

        // Collect potential android run configuration targets
        Set<Label> runConfigurationModuleTargets = Sets.newHashSet();

        // Get all explicitly mentioned targets
        // Doing this now will cut down on root changes later
        for (TargetExpression targetExpression : projectViewSet.listItems(TargetSection.KEY)) {
          if (!(targetExpression instanceof Label)) {
            continue;
          }
          Label label = (Label) targetExpression;
          runConfigurationModuleTargets.add(label);
        }
        // Get any pre-existing targets
        for (RunConfiguration runConfiguration :
            RunManager.getInstance(project).getAllConfigurationsList()) {
          BlazeAndroidRunConfigurationHandler handler =
              BlazeAndroidRunConfigurationHandler.getHandlerFrom(runConfiguration);
          if (handler == null) {
            continue;
          }
          runConfigurationModuleTargets.add(handler.getLabel());
        }

        int totalRunConfigurationModules = 0;
        for (Label label : runConfigurationModuleTargets) {
          TargetKey targetKey = TargetKey.forPlainTarget(label);
          // If it's a resource module, it will already have been created
          if (targetToAndroidResourceModule.containsKey(targetKey)) {
            continue;
          }
          // Ensure the label is a supported android rule that exists
          TargetIdeInfo target = blazeProjectData.targetMap.get(targetKey);
          if (target == null) {
            continue;
          }
          if (!target.kindIsOneOf(Kind.ANDROID_BINARY, Kind.ANDROID_TEST)) {
            continue;
          }

          String moduleName = moduleNameForAndroidModule(targetKey);
          Module module = moduleEditor.createModule(moduleName, StdModuleTypes.JAVA);
          ModifiableRootModel modifiableRootModel = moduleEditor.editModule(module);
          updateAndroidTargetModule(
              project,
              workspaceRoot,
              blazeProjectData.artifactLocationDecoder,
              androidSdkPlatform,
              target,
              module,
              modifiableRootModel,
              null);
          ++totalRunConfigurationModules;
        }

        context.output(
            PrintOutput.log(
                String.format(
                    "Android resource module count: %d, run config modules: %d, order entries: %d",
                    syncData.importResult.androidResourceModules.size(),
                    totalRunConfigurationModules,
                    totalOrderEntries)));
      }
    } else {
      AndroidFacetModuleCustomizer.removeAndroidFacet(workspaceModule);
    }

    LightResourceClassService.getInstance(project).installRClasses(rClassBuilder);
  }

  /** Ensures a suitable module exists for the given android target. */
  @Nullable
  public static Module ensureRunConfigurationModule(Project project, Label label) {
    TargetKey targetKey = TargetKey.forPlainTarget(label);
    String moduleName = moduleNameForAndroidModule(targetKey);
    Module module = ModuleManager.getInstance(project).findModuleByName(moduleName);
    if (module != null) {
      return module;
    }

    WorkspaceRoot workspaceRoot = WorkspaceRoot.fromProject(project);
    BlazeProjectData blazeProjectData =
        BlazeProjectDataManager.getInstance(project).getBlazeProjectData();
    if (blazeProjectData == null) {
      return null;
    }
    AndroidSdkPlatform androidSdkPlatform = SdkUtil.getAndroidSdkPlatform(blazeProjectData);
    if (androidSdkPlatform == null) {
      return null;
    }
    TargetIdeInfo target = blazeProjectData.targetMap.get(targetKey);
    if (target == null) {
      return null;
    }
    if (target.androidIdeInfo == null) {
      return null;
    }
    // We can't run a write action outside the dispatch thread, and can't
    // invokeAndWait it because the caller may have a read action.
    if (!ApplicationManager.getApplication().isDispatchThread()) {
      return null;
    }

    BlazeSyncPlugin.ModuleEditor moduleEditor =
        BlazeProjectDataManager.getInstance(project).editModules();
    Module newModule = moduleEditor.createModule(moduleName, StdModuleTypes.JAVA);
    ModifiableRootModel modifiableRootModel = moduleEditor.editModule(newModule);

    ApplicationManager.getApplication()
        .runWriteAction(
            () -> {
              updateAndroidTargetModule(
                  project,
                  workspaceRoot,
                  blazeProjectData.artifactLocationDecoder,
                  androidSdkPlatform,
                  target,
                  newModule,
                  modifiableRootModel,
                  null);
              moduleEditor.commit();
            });
    return newModule;
  }

  public static String moduleNameForAndroidModule(TargetKey targetKey) {
    return targetKey
        .toString()
        .substring(2) // Skip initial "//"
        .replace('/', '.')
        .replace(':', '.');
  }

  /** Updates the shared workspace module with android info. */
  private static void updateWorkspaceModule(
      Project project,
      WorkspaceRoot workspaceRoot,
      Module workspaceModule,
      AndroidSdkPlatform androidSdkPlatform) {
    File moduleDirectory = workspaceRoot.directory();
    File manifest = new File(workspaceRoot.directory(), "AndroidManifest.xml");
    String resourceJavaPackage = ":workspace";
    ImmutableList<File> transitiveResources = ImmutableList.of();

    createAndroidModel(
        project,
        androidSdkPlatform,
        workspaceModule,
        moduleDirectory,
        manifest,
        resourceJavaPackage,
        transitiveResources);
  }

  /** Updates a module from an android rule. */
  private static void updateAndroidTargetModule(
      Project project,
      WorkspaceRoot workspaceRoot,
      ArtifactLocationDecoder artifactLocationDecoder,
      AndroidSdkPlatform androidSdkPlatform,
      TargetIdeInfo target,
      Module module,
      ModifiableRootModel modifiableRootModel,
      @Nullable AndroidResourceModule androidResourceModule) {

    Collection<File> resources =
        androidResourceModule != null
            ? artifactLocationDecoder.decodeAll(androidResourceModule.resources)
            : ImmutableList.of();
    Collection<File> transitiveResources =
        androidResourceModule != null
            ? artifactLocationDecoder.decodeAll(androidResourceModule.transitiveResources)
            : ImmutableList.of();

    AndroidIdeInfo androidIdeInfo = target.androidIdeInfo;
    assert androidIdeInfo != null;

    File moduleDirectory = workspaceRoot.fileForPath(target.key.label.blazePackage());
    ArtifactLocation manifestArtifactLocation = androidIdeInfo.manifest;
    File manifest =
        manifestArtifactLocation != null
            ? artifactLocationDecoder.decode(manifestArtifactLocation)
            : new File(moduleDirectory, "AndroidManifest.xml");
    String resourceJavaPackage = androidIdeInfo.resourceJavaPackage;
    ResourceModuleContentRootCustomizer.setupContentRoots(modifiableRootModel, resources);

    createAndroidModel(
        project,
        androidSdkPlatform,
        module,
        moduleDirectory,
        manifest,
        resourceJavaPackage,
        transitiveResources);
  }

  private static void createAndroidModel(
      Project project,
      AndroidSdkPlatform androidSdkPlatform,
      Module module,
      File moduleDirectory,
      File manifest,
      String resourceJavaPackage,
      Collection<File> transitiveResources) {
    AndroidFacetModuleCustomizer.createAndroidFacet(module);
    SourceProvider sourceProvider =
        new SourceProviderImpl(module.getName(), manifest, transitiveResources);
    BlazeAndroidModel androidModel =
        new BlazeAndroidModel(
            project,
            module,
            moduleDirectory,
            sourceProvider,
            manifest,
            resourceJavaPackage,
            androidSdkPlatform.androidSdkLevel);
    AndroidFacet facet = AndroidFacet.getInstance(module);
    if (facet != null) {
      facet.setAndroidModel(androidModel);
    }
  }
}
