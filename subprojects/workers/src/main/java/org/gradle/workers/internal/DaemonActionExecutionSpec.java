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

package org.gradle.workers.internal;

import org.gradle.internal.UncheckedException;
import org.gradle.internal.io.ClassLoaderObjectInputStream;
import org.gradle.util.GUtil;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;

public class DaemonActionExecutionSpec implements ActionExecutionSpec {
    private final String displayName;
    private final byte[] delegateBytes;
    private final ClassLoaderStructure classLoaderStructure;

    public DaemonActionExecutionSpec(ActionExecutionSpec delegate, DaemonForkOptions forkOptions) {
        this.delegateBytes = GUtil.serialize(delegate);
        this.classLoaderStructure = forkOptions.getClassLoaderStructure();
        this.displayName = delegate.getDisplayName();
    }

    @Override
    public Class<?> getImplementationClass() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public Object[] getParams(ClassLoader classLoader) {
        throw new UnsupportedOperationException();
    }

    public ClassLoaderStructure getClassLoaderStructure() {
        return classLoaderStructure;
    }

    public ActionExecutionSpec getDelegate(ClassLoader classLoader) {
        ByteArrayInputStream bis = new ByteArrayInputStream(delegateBytes);
        try {
            ObjectInputStream ois = new ClassLoaderObjectInputStream(bis, classLoader);
            return (ActionExecutionSpec) ois.readObject();
        } catch (Exception e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }
}