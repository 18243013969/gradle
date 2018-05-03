/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.tasks.compile.incremental;

import com.google.common.collect.Lists;
import org.gradle.api.NonNullApi;
import org.gradle.api.internal.file.FileCollectionInternal;
import org.gradle.api.internal.file.FileCollectionVisitor;
import org.gradle.api.internal.file.FileTreeInternal;
import org.gradle.api.internal.file.collections.DirectoryFileTree;
import org.gradle.api.logging.Logging;

import java.io.File;
import java.util.List;

/**
 * Attempts to infer the source root directories for the `source` inputs to a
 * {@link org.gradle.api.tasks.compile.JavaCompile} task, in order to determine the `.class` file that corresponds
 * to any input source file.
 *
 * This is a bit of a hack: we'd be better off inspecting the actual source file to determine the name of the class file.
 */
@NonNullApi
public class CompilationSourceDirs {
    private static final org.gradle.api.logging.Logger LOG = Logging.getLogger(IncrementalCompilerDecorator.class);

    private final FileTreeInternal sources;
    private List<String> sourceRoots;
    private boolean canInferSourceRoots;

    public CompilationSourceDirs(FileTreeInternal sources) {
        this.sources = sources;
    }

    public List<String> getSourceRoots() {
        if (sourceRoots == null) {
            resolveRoots();
        }
        return sourceRoots;
    }

    public boolean canInferSourceRoots() {
        if (sourceRoots == null) {
            resolveRoots();
        }
        return canInferSourceRoots;
    }

    private void resolveRoots() {
        SourceRootVisitor visitor = new SourceRootVisitor();
        sources.visitRootElements(visitor);
        canInferSourceRoots = visitor.isCanInferSourceRoots();
        sourceRoots = visitor.getSourceRoots();
    }

    private static class SourceRootVisitor implements FileCollectionVisitor {
        private boolean canInferSourceRoots = true;
        private List<String> sourceRoots = Lists.newArrayList();

        @Override
        public void visitCollection(FileCollectionInternal fileCollection) {
            cannotInferSourceRoots(fileCollection);
        }

        @Override
        public void visitTree(FileTreeInternal fileTree) {
            cannotInferSourceRoots(fileTree);
        }

        @Override
        public void visitDirectoryTree(DirectoryFileTree directoryTree) {
            sourceRoots.add(absolutePath(directoryTree.getDir()));
        }

        private void cannotInferSourceRoots(FileCollectionInternal fileCollection) {
            canInferSourceRoots = false;
            LOG.info("Cannot infer source root(s) for source `{}`. Only things resolving to directory trees are supported.", fileCollection);
        }

        public boolean isCanInferSourceRoots() {
            return canInferSourceRoots;
        }

        public List<String> getSourceRoots() {
            return sourceRoots;
        }

        private String absolutePath(File source) {
            return source.getAbsolutePath() + File.separatorChar;
        }
    }
}
