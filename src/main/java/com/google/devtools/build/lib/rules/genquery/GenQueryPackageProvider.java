// Copyright 2022 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.rules.genquery;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.CachingPackageLocator;
import com.google.devtools.build.lib.packages.InputFile;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.pkgcache.PackageProvider;
import com.google.devtools.build.lib.rules.genquery.GenQueryPackageProviderFactory.BrokenQueryScopeException;
import com.google.devtools.build.lib.skyframe.TargetLoadingUtil;
import com.google.devtools.build.lib.skyframe.TargetLoadingUtil.TargetAndErrorIfAny;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import java.util.List;
import javax.annotation.Nullable;

/** Provide packages and targets to the query operations using precomputed transitive closure. */
final class GenQueryPackageProvider implements PackageProvider, CachingPackageLocator {

  private final ImmutableMap<PackageIdentifier, Package> pkgMap;
  private final ImmutableMap<Label, Target> labelToTarget;
  private final ImmutableMap<PackageIdentifier, Package> leafPackages;
  private final ImmutableMap<Label, Target> leafTargets;

  GenQueryPackageProvider(
      ImmutableMap<PackageIdentifier, Package> pkgMap, ImmutableMap<Label, Target> labelToTarget) {
    this(pkgMap, labelToTarget, ImmutableMap.of(), ImmutableMap.of());
  }

  private GenQueryPackageProvider(
      ImmutableMap<PackageIdentifier, Package> pkgMap,
      ImmutableMap<Label, Target> labelToTarget,
      ImmutableMap<PackageIdentifier, Package> leafPackages,
      ImmutableMap<Label, Target> leafTargets) {
    this.pkgMap = pkgMap;
    this.labelToTarget = labelToTarget;
    this.leafPackages = leafPackages;
    this.leafTargets = leafTargets;
  }

  @Nullable
  GenQueryPackageProvider withScopeLeaves(Environment env, List<Label> scopeLeaves)
      throws BrokenQueryScopeException, InterruptedException {
    if (scopeLeaves.isEmpty()) {
      return this;
    }

    // Keep the shared transitive scope intact. A leaf already in it has been fully visited.
    var packages = ImmutableMap.<PackageIdentifier, Package>builder();
    var targets = ImmutableMap.<Label, Target>builder();
    for (Label leaf : scopeLeaves) {
      if (labelToTarget.containsKey(leaf)) {
        continue;
      }
      try {
        if (TargetLoadingUtil.loadTarget(env, leaf) instanceof TargetAndErrorIfAny loaded) {
          if (!loaded.isPackageLoadedSuccessfully()) {
            throw BrokenQueryScopeException.of(loaded.getErrorLoadingTarget());
          }
          packages.put(leaf.getPackageIdentifier(), loaded.getPackage());
          targets.put(leaf, loaded.getTarget());
        }
      } catch (NoSuchTargetException | NoSuchPackageException e) {
        throw BrokenQueryScopeException.of(e);
      }
    }
    if (env.valuesMissing()) {
      return null;
    }
    ImmutableMap<Label, Target> extraTargets = targets.buildKeepingLast();
    return extraTargets.isEmpty()
        ? this
        : new GenQueryPackageProvider(pkgMap, labelToTarget, packages.buildKeepingLast(), extraTargets);
  }

  Predicate<Label> getValidTargetPredicate() {
    return leafTargets.isEmpty()
        ? Predicates.in(labelToTarget.keySet())
        : label -> labelToTarget.containsKey(label) || leafTargets.containsKey(label);
  }

  @Nullable
  private Package findPackage(PackageIdentifier packageId) {
    Package pkg = pkgMap.get(packageId);
    return pkg != null ? pkg : leafPackages.get(packageId);
  }

  @Override
  public Package getPackage(ExtendedEventHandler eventHandler, PackageIdentifier packageId)
      throws NoSuchPackageException {
    Package pkg = findPackage(packageId);
    if (pkg != null) {
      return pkg;
    }
    // Prefer to throw a checked exception on error; malformed genquery should not crash.
    throw new NoSuchPackageException(packageId, "is not within the scope of the query");
  }

  @Override
  public InputFile getBuildFile(ExtendedEventHandler eventHandler, PackageIdentifier packageId)
      throws NoSuchPackageException {
    return getPackage(eventHandler, packageId).getBuildFile();
  }

  @Override
  public Target getTarget(ExtendedEventHandler eventHandler, Label label)
      throws NoSuchPackageException, NoSuchTargetException {
    // Try to perform only one map lookup in the common case.
    Target target = labelToTarget.get(label);
    if (target == null) {
      target = leafTargets.get(label);
    }
    if (target != null) {
      return target;
    }
    // Prefer to throw a checked exception on error; malformed genquery should not crash.
    // Because it'd be more valuable, see if NoSuchPackageException should be thrown:
    Package unused = getPackage(eventHandler, label.getPackageIdentifier());
    throw new NoSuchTargetException(label, "is not within the scope of the query");
  }

  @Override
  public boolean isPackage(ExtendedEventHandler eventHandler, PackageIdentifier packageName) {
    throw new UnsupportedOperationException();
  }

  @Nullable
  @Override
  public Path getBuildFileForPackage(PackageIdentifier packageId) {
    Package pkg = findPackage(packageId);
    if (pkg == null) {
      return null;
    }
    return pkg.getBuildFile().getPath();
  }

  @Nullable
  @Override
  public String getBaseNameForLoadedPackage(PackageIdentifier packageName) {
    // TODO(b/123795023): we should have the data here but we don't have all packages for Starlark
    //  loads present here.
    Package pkg = findPackage(packageName);
    return pkg == null ? null : pkg.getBuildFileLabel().getName();
  }
}
