package com.bitdotgames.bhl.rider.lsp;

import com.intellij.platform.workspace.jps.entities.ContentRootEntity;
import com.intellij.platform.workspace.jps.entities.ModuleDependencyItem;
import com.intellij.platform.workspace.jps.entities.ModuleEntity;
import com.intellij.platform.workspace.storage.EntitySource;
import com.intellij.platform.workspace.storage.MutableEntityStorage;
import com.intellij.platform.workspace.storage.url.VirtualFileUrl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Constructs and registers a ModuleEntity + ContentRootEntity graph directly against the
 * workspace model, written in Java rather than Kotlin: ModuleEntity.create/ContentRootEntity
 * .create are Kotlin-internal to the platform's own module, and the Kotlin compiler rejects
 * calling them from plugin code even though the underlying bytecode is public — Kotlin's
 * "internal" visibility is enforced by the compiler reading Kotlin metadata, not by the JVM,
 * so plain Java has no such restriction (confirmed: the equivalent Kotlin call failed with
 * "Unresolved reference 'create'" despite javap showing the method as public bytecode).
 */
public final class BhlWorkspaceEntityHelper {
    private BhlWorkspaceEntityHelper() {
    }

    public static void registerModuleWithContentRoots(
            MutableEntityStorage storage,
            String moduleName,
            EntitySource entitySource,
            List<VirtualFileUrl> roots
    ) {
        ModuleEntity.Builder moduleBuilder =
                ModuleEntity.create(moduleName, Collections.<ModuleDependencyItem>emptyList(), entitySource);
        List<ContentRootEntity.Builder> contentRoots = new ArrayList<>();
        for (VirtualFileUrl root : roots) {
            contentRoots.add(ContentRootEntity.create(root, Collections.<String>emptyList(), entitySource));
        }
        moduleBuilder.setContentRoots(contentRoots);
        storage.addEntity(moduleBuilder);
    }
}
