/*
 * Copyright (c) 2013, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
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

package com.sun.javatest.regtest.config;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import com.sun.javatest.regtest.util.GraphUtils;
import com.sun.javatest.regtest.util.GraphUtils.Node;
import com.sun.javatest.regtest.util.GraphUtils.TarjanNode;

/**
 * Manage test groups, for use on the jtreg command line.
 */
public class GroupManager {
    public static void main(String... args) throws Exception {
        File root = new File(args[0]);
        List<String> files = new ArrayList<>();
        for (int i = 1; i < args.length; i++)
            files.add(args[i]);
        PrintWriter out = new PrintWriter(System.err);
        try {
            GroupManager gm = new GroupManager(out, root, files);
            gm.setIgnoredDirectories(Arrays.asList("SCCS"));
            gm.setAllowedExtensions(Arrays.asList(".java", ".sh", ".html"));
            for (Group g: gm.groups.values())
                System.err.println(g.name + ": " + g.getFiles());
        } finally {
            out.flush();
        }
    }

    public static final String GROUP_PREFIX = ":";
    public static final String EXCLUDE_PREFIX = "-";

    final PrintWriter out;
    final File root;
    final Map<String, Group> groups = new HashMap<>();
    private Collection<String> ignoreDirs = Collections.emptySet();
    private Collection<String> allowExtns = Collections.emptySet();

    public class InvalidGroup extends Exception {
        private static final long serialVersionUID = 1L;
    }

    GroupManager(PrintWriter out, File root, List<String> files) throws IOException {
        this.out = out;
        this.root = root;

        for (String f: files) {
            boolean optional;
            if (f.startsWith("[") && f.endsWith("]")) {
                f = f.substring(1, f.length() - 1);
                optional = true;
            } else
                optional = false;

            File file = new File(root, f);
            FileInputStream fin;
            try {
                fin = new FileInputStream(file);
            } catch (FileNotFoundException e) {
                if (optional)
                    continue;
                throw e;
            }

            try {
                Properties p = new Properties();
                p.load(new BufferedInputStream(fin));
                for (Map.Entry<Object,Object> e: p.entrySet()) {
                    String groupName = (String) e.getKey();
                    String entryDef = (String) e.getValue();
                    Group g = getGroup(groupName);
                    g.addEntry(new Entry(file, root, entryDef));
                }
            } finally {
                fin.close();
            }
        }

        validate();
    }

    void setAllowedExtensions(Collection<String> extns) {
        allowExtns = new HashSet<>(extns);
    }

    void setIgnoredDirectories(Collection<String> names) {
        ignoreDirs = new HashSet<>(names);
    }

    public Set<File> getFiles(String group) throws InvalidGroup {
        Group g = getGroup(group);
        if (g.invalid)
            throw new InvalidGroup();
        return g.getFiles();
    }

    private Group getGroup(String name) {
        Group g = groups.get(name);
        if (g == null)
           groups.put(name, g = new Group(name));
        return g;
    }

    public Set<String> getGroups() {
        return groups.keySet();
    }

    private void validate() {
        for (Group g: groups.values()) {
            for (Entry e: g.entries) {
                @SuppressWarnings("unchecked")
                List<Set<File>> allFiles = Arrays.asList(e.includeFiles, e.excludeFiles);
                for (Set<File> files: allFiles) {
                    for (File f: files) {
                        if (!f.exists()) {
                            URI u = root.toURI().relativize(f.toURI());
                            error(e.origin, g, "file not found: " + u.getPath());
                        }
                    }
                }
                for (Group eg: e.includeGroups) {
                    if (eg.isEmpty())
                        error(e.origin, g, "group not found: " + eg.name);
                    if (eg == g)
                        error(e.origin, g, "group includes itself");
                }
            }
        }

        final Map<Group, TarjanNode<Group>> nodes = new HashMap<>();
        for (Group g: groups.values()) {
            nodes.put(g, new TarjanNode<Group>(g) {
                @Override
                public Iterable<? extends TarjanNode<Group>> getDependencies() {
                    List<TarjanNode<Group>> deps = new ArrayList<> ();
                    for (Entry e: data.entries) {
                        for (Group g: e.includeGroups) {
                            deps.add(nodes.get(g));
                        }
                    }
                    return deps;
                }

                @Override
                public String printDependency(Node<Group> to) {
                    return to.data.name;
                }
            });
        }
        Set<? extends Set<? extends TarjanNode<Group>>> cycles = GraphUtils.tarjan(nodes.values());
    }

    private void error(File f, Group g, String message) {
        out.println(f + ": group " + g + ": " + message);
        g.invalid = true;
    }

    private class Group {
        final String name;
        final List<Entry> entries;
        private Set<File> files;
        boolean invalid;

        Group(String name) {
            this.name = name;
            entries = new ArrayList<>();
        }

        boolean isEmpty() {
            return entries.isEmpty();
        }

        void addEntry(Entry e) {
            entries.add(e);
        }

        Set<File> getFiles() {
            if (files == null) {
                files = new LinkedHashSet<>();
                Set<File> inclFiles = new HashSet<>();
                Set<File> exclFiles = new HashSet<>();
                for (Entry e: entries) {
                    inclFiles.addAll(e.includeFiles);
                    for (Group g: e.includeGroups)
                        inclFiles.addAll(g.getFiles());
                    exclFiles.addAll(e.excludeFiles);
                    for (Group g: e.excludeGroups)
                        exclFiles.addAll(g.getFiles());
                }
                addFiles(files, inclFiles, exclFiles);
            }
            return files;
        }

        private void addFiles(Collection<File> files, Collection<File> includes, Collection<File> excludes) {
            for (File incl: includes) {
                if (contains(files, incl) || contains(excludes, incl))
                    continue;

                if (incl.isFile())
                    addFile(files, incl);
                else if (incl.isDirectory()) {
                    Set<File> excludesForIncl = filter(incl, excludes);
                    if (excludesForIncl.isEmpty())
                        addFile(files, incl);
                    else
                        addFiles(files, list(incl), excludesForIncl);
                }
            }
        }

        private void addFile(Collection<File> files, File file) {
            for (Iterator<File> iter = files.iterator(); iter.hasNext(); ) {
                File f = iter.next();
                if (contains(file, f))
                    iter.remove();
            }
            files.add(file);
        }

        private boolean contains(Collection<File> files, File file) {
            for (File f: files) {
                if (f.equals(file) || contains(f, file)) {
                    return true;
                }
            }
            return false;
        }

        private boolean contains(File dir, File file) {
            String dirPath = dir.getPath();
            if (!dirPath.endsWith(File.separator))
                dirPath += File.separator;
            return file.getPath().startsWith(dirPath);
        }

        private Set<File> filter(File dir, Collection<File> files) {
            Set<File> results = null;
            String dirPath = dir.getPath();
            if (!dirPath.endsWith(File.separator))
                dirPath += File.separator;
            for (File f: files) {
                String fp = f.getPath();
                if (fp.startsWith(dirPath)) {
                    if (results == null) results = new LinkedHashSet<>();
                    results.add(f);
                }
            }
            return results == null ? Collections.<File>emptySet() : results;
        }

        private List<File> list(File file) {
            List<File> children = new ArrayList<>();
            for (File f: file.listFiles()) {
                String fn = f.getName();
                if (f.isDirectory() && !ignoreDirs.contains(fn)
                        || f.isFile() && allowExtns.contains(getExtension(fn)))
                    children.add(f);
            }
            return children;
        }

        private String getExtension(String name) {
            int sep = name.lastIndexOf(".");
            return (sep == -1) ? null : name.substring(sep);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    class Entry {
        final File origin;

        final Set<File> includeFiles = new LinkedHashSet<>();
        final Set<File> excludeFiles = new LinkedHashSet<>();
        final Set<Group> includeGroups = new LinkedHashSet<>();
        final Set<Group> excludeGroups = new LinkedHashSet<>();

        Entry(File origin, File root, String def) {
            this.origin = origin;

            def = def.trim();
            if (def.length() == 0)
                return;

            for (String item: def.split("\\s+")) {
                boolean exclude = item.startsWith(EXCLUDE_PREFIX);
                if (exclude)
                    item = item.substring(1);

                if (item.startsWith(GROUP_PREFIX)) {
                    String name = item.substring(1);
                    (exclude ? excludeGroups : includeGroups).add(getGroup(name));
                } else {
                    String name = item;
                    if (name.startsWith("/"))
                        name = name.substring(1);
                    if (name.endsWith("/"))
                        name = name.substring(0, name.length() - 1);
                    File f = name.equals("") ? root : new File(root, name);
                    (exclude ? excludeFiles : includeFiles).add(f);
                }
            }
        }

        @Override
        public String toString() {
            return "Entry[origin:" + origin
                    + "inclFiles:" + includeFiles
                    + ",exclFiles:" + excludeFiles
                    + ",inclGroups:" + includeGroups
                    + ",exclGroups:" + excludeGroups
                    + "]";
        }
    }
}
