/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the Classpath exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.sun.btrace.runtime;

import com.sun.btrace.org.objectweb.asm.AnnotationVisitor;
import com.sun.btrace.org.objectweb.asm.Attribute;
import com.sun.btrace.org.objectweb.asm.ClassReader;
import com.sun.btrace.org.objectweb.asm.ClassVisitor;
import com.sun.btrace.org.objectweb.asm.Opcodes;
import com.sun.btrace.org.objectweb.asm.Type;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashSet;

/**
 * A hacked version of <a href="http://asm.ow2.org/asm50/javadoc/user/org/objectweb/asm/ClassReader.html">ClassReader</a>
 * allowing fast access to class name, class version, super type, interfaces and annotations.
 * @author Jaroslav Bachorik
 */
final class BTraceClassReader extends ClassReader {
    private static final Method getAttributesMthd;
    private static final Method readAnnotationValuesMthd;
    private static final Field itemsFld;

    static {
        Method m1 = null, m2 = null;
        Field f = null;
        try {
            m1 = ClassReader.class.getDeclaredMethod("a");
            m1.setAccessible(true);

            m2 = ClassReader.class.getDeclaredMethod(
                "a",
                int.class, char[].class,
                boolean.class, AnnotationVisitor.class
            );
            m2.setAccessible(true);

            f = ClassReader.class.getDeclaredField("a");
            f.setAccessible(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
        getAttributesMthd = m1;
        readAnnotationValuesMthd = m2;
        itemsFld = f;
    }
    /**
     * Dummy, non-stack-collecting runtime exception. It is used for execution control in ClassReader instances in order
     * to avoid processing the complete class file when the relevant info is available right at the beginning of
     * parsing.
     */
    private static final class BailoutException extends RuntimeException {
        /**
         * Shared instance to optimize the cost of throwing
         */
        private static final BailoutException INSTANCE = new BailoutException();

        private BailoutException() {
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            // we don't need the stack here
            return this;
        }
    }

    private final ClassLoader cl;

    BTraceClassReader(ClassLoader cl, byte[] bytes) {
        super(bytes);
        this.cl = cl;
    }

    BTraceClassReader(ClassLoader cl, InputStream in) throws IOException {
        super(in);
        this.cl = cl;
    }

    public ClassLoader getClassLoader() {
        return cl;
    }

    /**
     * The associated Java class name ('.' is the package delimiter)
     * @return
     */
    public String getJavaClassName() {
        return getClassName().replace('/', '.');
    }

    public String[] readClassSupers() {
        int u = header; // current offset in the class file
        char[] c = new char[getMaxStringLength()]; // buffer used to read strings

        // reads the class declaration
        int ifcsLen = readUnsignedShort(u + 6);
        String[] info = new String[ifcsLen + 1];
        // supertype name
        info[0] = readClass(u + 4, c);
        u += 8;
        // interfaces, if any
        for (int i = 0; i < ifcsLen; ++i) {
            info[1 + i] = readClass(u, c);
            u += 2;
        }
        return info;
    }

    public boolean isInterface() {
        return (getAccess() & Opcodes.ACC_INTERFACE) != 0;
    }

    public boolean isBTrace() {
        return getAnnotationTypes().contains(Constants.BTRACE_DESC);
    }

    public Collection<String> getAnnotationTypes() {
        Collection<String> types = new HashSet<>();

        char[] c = new char[getMaxStringLength()]; // buffer used to read strings
        int anns = getAnnotationsOffset(c);
        if (anns != -1) {
            for (int i = readUnsignedShort(anns), v = anns + 2; i > 0; --i) {
                types.add(Type.getType(readUTF8(v, c)).getClassName());
                v = skipAnnotationValues(v + 2, c);
                if (v == -1) break;
            }
        }
        return types;
    }

    public int getClassVersion() {
        try {
            if (itemsFld != null) {
                int[] items = (int[])itemsFld.get(this);
                return readInt(items[1] - 7);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 51; // default to Java 7
    }

    @Override
    public void accept(ClassVisitor cv, Attribute[] atrbts, int i) {
        try {
            super.accept(cv, atrbts, i);
        } catch (BailoutException e) {
            // expected; ignore
        }
    }

    @Override
    public void accept(ClassVisitor cv, int i) {
        try {
            super.accept(cv, i);
        } catch (BailoutException e) {
            // expected; ignore
        }
    }

    public static void bailout() {
        throw BailoutException.INSTANCE;
    }

    private int getAttributes() {
        try {
            if (getAttributesMthd != null) {
                return (int)getAttributesMthd.invoke(this);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return -1;
    }

    private int getAnnotationsOffset(char[] buf) {
        int u = getAttributes();
        if (u == -1) {
            return -1;
        }

        for (int i = readUnsignedShort(u); i > 0; --i) {
            String attrName = readUTF8(u + 2, buf);

            if ("RuntimeVisibleAnnotations".equals(attrName)) {
                return  u + 8;
            }
            u += 6 + readInt(u + 4);
        }
        return -1;
    }

    private int skipAnnotationValues(int off, char[] buf) {
        try {
            if (readAnnotationValuesMthd != null) {
                return (int) readAnnotationValuesMthd.invoke(this, off, buf, true, null);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return -1;
    }
}
