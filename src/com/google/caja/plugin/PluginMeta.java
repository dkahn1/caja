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

/**
 * For a plugin, determines how its external dependencies are translated.
 */
public final class PluginMeta {
  /** Used to generate names that are unique within the plugin's namespace. */
  private int guidCounter;
  /** Describes how resources external to the plugin definition are resolved. */
  private final PluginEnvironment env;
  /** True if the output should include debugging info. */
  private boolean debugMode;
  /** True if the source should be treated as Valija */
  private boolean valijaMode;

  public PluginMeta() {
    this(PluginEnvironment.CLOSED_PLUGIN_ENVIRONMENT);
  }

  public PluginMeta(PluginEnvironment env) {
    if (env == null) { throw new NullPointerException(); }
    this.env = env;
  }

  /**
   * Generates a name that can be used as an identifier in the plugin's
   * namespace.
   * @param prefix a valid javascript identifier prefix.
   */
  public String generateUniqueName(String prefix) {
    return prefix + "_" + (++guidCounter) + "___";
  }

  /** Describes how resources external to the plugin definition are resolved. */
  public PluginEnvironment getPluginEnvironment() { return env; }

  /** True iff the output should include debugging info. */
  public boolean isDebugMode() { return debugMode; }

  public void setDebugMode(boolean debugMode) { this.debugMode = debugMode; }

  /** True if the source should be treated as Valija. */
  public boolean isValijaMode() { return valijaMode; }

  public void setValijaMode(boolean valijaMode) { this.valijaMode = valijaMode; }
}
