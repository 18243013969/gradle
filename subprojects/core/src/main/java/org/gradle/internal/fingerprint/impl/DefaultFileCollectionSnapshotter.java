/*
 * Copyright 2019 the original author or authors.
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

package org.gradle.internal.fingerprint.impl;

import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileVisitDetails;
import org.gradle.api.file.FileVisitor;
import org.gradle.api.internal.file.FileCollectionInternal;
import org.gradle.api.internal.file.FileCollectionStructureVisitor;
import org.gradle.api.internal.file.FileTreeInternal;
import org.gradle.api.tasks.util.PatternSet;
import org.gradle.internal.file.FileType;
import org.gradle.internal.file.Stat;
import org.gradle.internal.file.impl.DefaultFileMetadata;
import org.gradle.internal.fingerprint.FileCollectionSnapshotter;
import org.gradle.internal.snapshot.FileSystemLocationSnapshot;
import org.gradle.internal.snapshot.FileSystemSnapshot;
import org.gradle.internal.snapshot.MerkleDirectorySnapshotBuilder;
import org.gradle.internal.vfs.VirtualFileSystem;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class DefaultFileCollectionSnapshotter implements FileCollectionSnapshotter {
    private final VirtualFileSystem virtualFileSystem;
    private final Stat stat;

    public DefaultFileCollectionSnapshotter(VirtualFileSystem virtualFileSystem, Stat stat) {
        this.virtualFileSystem = virtualFileSystem;
        this.stat = stat;
    }

    @Override
    public List<FileSystemSnapshot> snapshot(FileCollection fileCollection) {
        SnapshotingVisitor visitor = new SnapshotingVisitor();
        ((FileCollectionInternal) fileCollection).visitStructure(visitor);
        return visitor.getRoots();
    }


    private class SnapshotingVisitor implements FileCollectionStructureVisitor {
        private final List<FileSystemSnapshot> roots = new ArrayList<>();

        @Override
        public void visitCollection(FileCollectionInternal.Source source, Iterable<File> contents) {
            for (File file : contents) {
                snapshotFile(file);
            }
        }

        @Override
        public void visitGenericFileTree(FileTreeInternal fileTree) {
            roots.add(snapshotFileTree(fileTree));
        }

        @Override
        public void visitFileTree(File root, PatternSet patterns, FileTreeInternal fileTree) {
            MerkleDirectorySnapshotBuilder builder = MerkleDirectorySnapshotBuilder.sortingRequired();
            virtualFileSystem.read(root.getAbsolutePath(), new PatternSetSnapshottingFilter(patterns, stat), builder);
            FileSystemLocationSnapshot result = builder.getResult();
            roots.add((result == null || result.getType() == FileType.Missing) ? FileSystemSnapshot.EMPTY : result);
        }

        @Override
        public void visitFileTreeBackedByFile(File file, FileTreeInternal fileTree) {
            snapshotFile(file);
        }

        private void snapshotFile(File file) {
            MerkleDirectorySnapshotBuilder builder = MerkleDirectorySnapshotBuilder.sortingRequired();
            virtualFileSystem.read(file.getAbsolutePath(), builder);
            roots.add(builder.getResult());
        }

        public List<FileSystemSnapshot> getRoots() {
            return roots;
        }
    }

    private FileSystemSnapshot snapshotFileTree(FileTreeInternal tree) {
        return virtualFileSystem.snapshotWithBuilder(builder -> tree.visit(new FileVisitor() {
            @Override
            public void visitDir(FileVisitDetails dirDetails) {
                builder.addDir(
                    dirDetails.getFile(),
                    dirDetails.getRelativePath().getSegments()
                );
            }

            @Override
            public void visitFile(FileVisitDetails fileDetails) {
                builder.addFile(
                    fileDetails.getFile(),
                    fileDetails.getRelativePath().getSegments(),
                    fileDetails.getName(),
                    new DefaultFileMetadata(
                        FileType.RegularFile,
                        fileDetails.getLastModified(),
                        fileDetails.getSize()
                    )
                );
            }
        }));
    }
}
