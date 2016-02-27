/*
 * Copyright (c) 2014, 2016, Oracle and/or its affiliates. All rights reserved.
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

package java.lang.module;

import java.io.File;
import java.io.FilePermission;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.Permission;
import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A finder of modules. A {@code ModuleFinder} is used to find modules during
 * <a href="Configuration.html#resolution">resolution</a> or
 * <a href="Configuration.html#servicebinding">service binding</a>.
 *
 * <p> A {@code ModuleFinder} can only find one module with a given name. A
 * {@code ModuleFinder} that finds modules in a sequence of directories, for
 * example, will locate the first occurrence of a module of a given name and
 * will ignore other modules of that name that appear directories later in the
 * sequence. </p>
 *
 * <p> Example usage: </p>
 *
 * <pre>{@code
 *     Path dir1, dir2, dir3;
 *
 *     ModuleFinder finder = ModuleFinder.of(dir1, dir2, dir3);
 *
 *     Optional<ModuleReference> omref = finder.find("jdk.foo");
 *     if (omref.isPresent()) { ... }
 *
 * }</pre>
 *
 * <p> The {@link #find(String) find} and {@link #findAll() findAll} methods
 * defined here can fail for several reasons. These include include I/O errors,
 * errors detected parsing a module descriptor ({@code module-info.class}), or
 * in the case of {@code ModuleFinder} returned by {@link #of ModuleFinder.of},
 * that two or more modules with the same name are found in a directory.
 * When an error is detected then these methods throw {@link FindException
 * FindException} with an appropriate {@link Throwable#getCause cause}.
 * The behavior of a {@code ModuleFinder} after a {@code FindException} is
 * thrown is undefined. For example, invoking {@code find} after an exception
 * is thrown may or may not scan the same modules that lead to the exception.
 * It is recommended that a module finder be discarded after an exception is
 * thrown. </p>
 *
 * <p> A {@code ModuleFinder} is not required to be thread safe. </p>
 *
 * @since 9
 */

public interface ModuleFinder {

    /**
     * Finds a reference to a module of a given name.
     *
     * <p> A {@code ModuleFinder} provides a consistent view of the
     * modules that it locates. If {@code find} is invoked several times to
     * locate the same module (by name) then it will return the same result
     * each time. If a module is located then it is guaranteed to be a member
     * of the set of modules returned by the {@link #findAll() findAll}
     * method. </p>
     *
     * @param  name
     *         The name of the module to find
     *
     * @return A reference to a module with the given name or an empty
     *         {@code Optional} if not found
     *
     * @throws FindException
     *         If an error occurs finding the module
     *
     * @throws SecurityException
     *         If denied by the security manager
     */
    public Optional<ModuleReference> find(String name);

    /**
     * Returns the set of all module references that this finder can locate.
     *
     * <p> A {@code ModuleFinder} provides a consistent view of the
     * modules that it locates. If {@link #findAll() findAll} is invoked
     * several times then it will return the same (equals) result each time.
     * For each {@code ModuleReference} element of the returned set then it is
     * guaranteed that that {@link #find find} will locate that {@code
     * ModuleReference} if invoked with the module name. </p>
     *
     * @apiNote This is important to have for methods such as {@link
     * Configuration#bind} that need to scan the module path to find
     * modules that provide a specific service.
     *
     * @return The set of all module references that this finder locates
     *
     * @throws FindException
     *         If an error occurs finding all modules
     *
     * @throws SecurityException
     *         If denied by the security manager
     */
    public Set<ModuleReference> findAll();

    /**
     * Returns a module finder for modules that are linked into the run-time
     * image.
     *
     * <p> If there is a security manager set then its {@link
     * SecurityManager#checkPermission(Permission) checkPermission} method is
     * invoked to check that the caller has been granted {@link FilePermission}
     * to recursively read the directory that is the value of the system
     * property {@code java.home}. </p>
     *
     * @implNote For now, this method returns a module finder that finds all
     * modules in the run-time image. In the future then there may be modules
     * in the run-time image that aren't candidates for the boot Layer. In
     * that case then the module finder returned by this method may only find
     * a subset of the observable modules.
     *
     * @return A {@code ModuleFinder} that locates all modules in the
     *         run-time image
     *
     * @throws SecurityException
     *         If denied by the security manager
     */
    public static ModuleFinder ofInstalled() {
        String home;

        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            PrivilegedAction<String> pa = () -> System.getProperty("java.home");
            home = AccessController.doPrivileged(pa);
            Permission p = new FilePermission(home + File.separator + "-", "read");
            sm.checkPermission(p);
        } else {
            home = System.getProperty("java.home");
        }

        Path modules = Paths.get(home, "lib", "modules");
        if (Files.isRegularFile(modules)) {
            return new InstalledModuleFinder();
        } else {
            Path mlib = Paths.get(home, "modules");
            if (Files.isDirectory(mlib)) {
                return of(mlib);
            } else {
                throw new InternalError("Unable to detect the run-time image");
            }
        }
    }

    /**
     * Creates a module finder that locates modules on the file system by
     * searching a sequence of directories and/or packaged modules.
     *
     * Each element in the given array is one of:
     * <ol>
     *     <li><p> A path to a directory of modules.</p></li>
     *     <li><p> A path to the <em>top-level</em> directory of an
     *         <em>exploded module</em>. </p></li>
     *     <li><p> A path to a <em>packaged module</em>. </p></li>
     * </ol>
     *
     * The module finder locates modules by searching each directory, exploded
     * module, or packaged module in array index order. It finds the first
     * occurrence of a module with a given name and ignores other modules of
     * that name that appear later in the sequence.
     *
     * <p> If an element is a path to a directory of modules then each entry in
     * the directory is a packaged module or the top-level directory of an
     * exploded module. The module finder's {@link #find(String) find} or
     * {@link #findAll() findAll} methods throw {@link FindException} if a
     * directory containing more than one module with the same name is
     * encountered. </p>
     *
     * <p> If an element in the array is a path to a directory, and that
     * directory contains a file named {@code module-info.class}, then the
     * directory is treated as an exploded module rather than a directory of
     * modules. </p>
     *
     * <p> The module finder returned by this method supports modules that are
     * packaged as JAR files. A JAR file with a {@code module-info.class} in
     * the top-level directory of the JAR file is a modular JAR and is an
     * <em>explicit module</em>. A JAR file that does not have a {@code
     * module-info.class} in the top-level directory is an {@link
     * ModuleDescriptor#isAutomatic automatic} module. The {@link
     * ModuleDescriptor} for an automatic module is created as follows:
     *
     * <ul>
     *
     *     <li><p> The module {@link ModuleDescriptor#name() name}, and {@link
     *     ModuleDescriptor#version() version} if applicable, is derived from
     *     the file name of the JAR file as follows: </p>
     *
     *     <ul>
     *
     *         <li><p> The {@code .jar} suffix is removed. </p></li>
     *
     *         <li><p> If the name matches the regular expression {@code
     *         "-(\\d+(\\.|$))"} then the module name will be derived from the
     *         subsequence proceeding the hyphen. The subsequence after the
     *         hyphen is parsed as a {@link ModuleDescriptor.Version} and
     *         ignored if it cannot be parsed as a {@code Version}. </p></li>
     *
     *         <li><p> For the module name, then all non-alphanumeric
     *         characters ({@code [^A-Za-z0-9])} are replaced with a dot
     *         ({@code "."}), all repeating dots are replaced with one dot,
     *         and all leading and trailing dots are removed. </p></li>
     *
     *         <li><p> As an example, a JAR file named {@code foo-bar.jar} will
     *         derive a module name {@code foo.bar} and no version. A JAR file
     *         named {@code foo-1.2.3-SNAPSHOT.jar} will derive a module name
     *         {@code foo} and {@code 1.2.3-SNAPSHOT} as the version. </p></li>
     *
     *     </ul></li>
     *
     *     <li><p> It {@link ModuleDescriptor#requires() requires} {@code
     *     java.base}. </p></li>
     *
     *     <li><p> All entries in the JAR file with names ending with {@code
     *     .class} are assumed to be class files where the name corresponds
     *     to the fully qualified name of the class. The packages of all
     *     classes are {@link ModuleDescriptor#exports() exported}. </p></li>
     *
     *     <li><p> The contents of all entries starting with {@code
     *     META-INF/services/} are assumed to be service configuration files
     *     (see {@link java.util.ServiceLoader}). The name of the file
     *     (that follows {@code META-INF/services/}) is assumed to be the
     *     fully-qualified binary name of a service type. The entries in the
     *     file are assumed to be the fully-qualified binary names of
     *     provider classes. </p></li>
     *
     *     <li><p> If the JAR file has a {@code Main-Class} attribute in its
     *     main manifest then its value is the {@link
     *     ModuleDescriptor#mainClass() main class}. </p></li>
     *
     * </ul>
     *
     * <p> In addition to JAR files, an implementation may also support modules
     * that are packaged in other implementation specific module formats. As
     * with automatic modules, the contents of a packaged or exploded module
     * may need to be <em>scanned</em> in order to determine the packages in
     * the module. If a {@code .class} file that corresponds to a class in an
     * unnamed package is encountered then {@code FindException} is thrown. </p>
     *
     * <p> Finders created by this method are lazy and do not eagerly check
     * that the given file paths are directories or packaged modules.
     * Consequently, the {@code find} or {@code findAll} methods will only
     * fail if invoking these methods results in searching a directory or
     * packaged module and an error is encountered. Paths to files that do not
     * exist are ignored. </p>
     *
     * @apiNote This method is not required to be thread safe.
     *
     * @param entries
     *        A possibly-empty array of paths to directories of modules
     *        or paths to packaged or exploded modules
     *
     * @return A {@code ModuleFinder} that locates modules on the file system
     */
    public static ModuleFinder of(Path... entries) {
        return new ModulePath(entries);
    }

    /**
     * Returns a module finder that is the equivalent to concatenating two
     * module finders. The resulting finder will locate modules references
     * using {@code first}; if not found then it will attempt to locate module
     * references using {@code second}.
     *
     * <p> The {@link #findAll() findAll} method of the resulting module finder
     * will locate all modules located by the first module finder. It will
     * also locate all modules located by the second module finder that are not
     * located by the first module finder. </p>
     *
     * @param first
     *        The first module finder
     * @param second
     *        The second module finder
     *
     * @return A {@code ModuleFinder} that concatenates two module finders
     */
    public static ModuleFinder concat(ModuleFinder first, ModuleFinder second) {
        Objects.requireNonNull(first);
        Objects.requireNonNull(second);

        return new ModuleFinder() {
            Set<ModuleReference> allModules;

            @Override
            public Optional<ModuleReference> find(String name) {
                Optional<ModuleReference> om = first.find(name);
                if (!om.isPresent())
                    om = second.find(name);
                return om;
            }
            @Override
            public Set<ModuleReference> findAll() {
                if (allModules == null) {
                    allModules = Stream.concat(first.findAll().stream(),
                                               second.findAll().stream())
                                       .map(a -> a.descriptor().name())
                                       .distinct()
                                       .map(this::find)
                                       .map(Optional::get)
                                       .collect(Collectors.toSet());
                }
                return allModules;
            }
        };
    }

    /**
     * Returns an empty module finder.  The empty finder does not find any
     * modules.
     *
     * @apiNote This is useful when using methods such as {@link
     * Configuration#resolve resolve} where two finders are specified.
     *
     * @return A {@code ModuleFinder} that does not find any modules
     */
    public static ModuleFinder empty() {
        // an alternative implementation of ModuleFinder.of()
        return new ModuleFinder() {
            @Override public Optional<ModuleReference> find(String name) {
                Objects.requireNonNull(name);
                return Optional.empty();
            }
            @Override public Set<ModuleReference> findAll() {
                return Collections.emptySet();
            }
        };
    }

}
