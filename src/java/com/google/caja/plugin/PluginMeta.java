// Copyright (C) 2006 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.caja.plugin;

import java.util.regex.Pattern;

/**
 * For a plugin, determines how its external dependencies are translated.
 */
public final class PluginMeta {
  /** The name of the javascript global variable that contains the namespace. */
  public final String namespaceName;
  /**
   * The name of the javascript global that contains this object's state.
   */
  public final String namespacePrivateName;
  /** A CSS&HTML identifier prefix used to namespace ids and classes. */
  public final String namespacePrefix;
  /** A URI path prefix used to group all the files together. */
  public final String pathPrefix;
  /**
   * The id of a DOM element which will be assumed to exist wherever the
   * rewritten plugin is spit out, and into which it will attempt to inject
   * itself.
   *
   * <p>TODO: we shouldn't assume a single rootDomId. Move this field to a
   * subclass, so that client apps can pass in a specific PluginMeta if they're
   * comfortable assuming a single rootId.
   */
  public final String rootDomId;
  /** Are we doing Baja translations? */
  public final boolean isBaja;
  /** Used to generate names that are unique within the plugin's namespace. */
  private int guidCounter;

  public PluginMeta(String namespaceName, String namespacePrefix,
                    String rootDomId, boolean isBaja) {
    this(namespaceName, namespacePrefix, "", rootDomId, isBaja);
  }

  public PluginMeta(String namespaceName, String namespacePrefix,
                    String pathPrefix, String rootDomId, boolean isBaja) {
    if (null == namespaceName || null == namespacePrefix
        || null == pathPrefix || null == rootDomId) {
      throw new NullPointerException();
    }
    if (pathPrefix.endsWith("/") && pathPrefix.length() > 1) {
      throw new IllegalArgumentException(pathPrefix);
    }
    if (!pathPrefix.startsWith("/") && !"".equals(pathPrefix)) {
      throw new IllegalArgumentException(pathPrefix);
    }
    this.namespaceName = namespaceName;
    this.namespacePrefix = namespacePrefix;
    this.pathPrefix = pathPrefix;
    this.rootDomId = rootDomId;
    this.isBaja = isBaja;
    if (CONSTANT_NAME.matcher(this.namespaceName).matches()) {
      this.namespacePrivateName = this.namespaceName + "_PRIVATE";
    } else {
      this.namespacePrivateName = this.namespaceName + "Private";
    }
  }

  /**
   * Generates a name that can be used as an identifier in the plugin's
   * namespace.
   * @param prefix a valid javascript identifier prefix.
   */
  public String generateUniqueName(String prefix) {
    return prefix + "_" + (++guidCounter) + "___";
  }

  private static final Pattern CONSTANT_NAME = Pattern.compile("^[A-Z_]+$");
}
