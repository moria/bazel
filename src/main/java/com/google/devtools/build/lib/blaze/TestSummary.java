// Copyright 2014 Google Inc. All rights reserved.
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
package com.google.devtools.build.lib.blaze;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.util.io.AnsiTerminalPrinter.Mode;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.view.ConfiguredTarget;
import com.google.devtools.build.lib.view.FilesToRunProvider;
import com.google.devtools.build.lib.view.test.BlazeTestStatus;
import com.google.devtools.build.lib.view.test.TestResultData.FailedTestCaseDetails;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Test summary entry. Stores summary information for a single test rule.
 * Also used to sort summary output by status.
 *
 * <p>Invariant:
 * All TestSummary mutations should be performed through the Builder.
 * No direct TestSummary methods (except the constructor) may mutate the object.
 */
@VisibleForTesting // Ideally package-scoped.
public class TestSummary implements Comparable<TestSummary> {
  /**
   * Builder class responsible for creating and altering TestSummary objects.
   */
  public static class Builder {
    private TestSummary summary;
    private boolean built;

    private Builder() {
      summary = new TestSummary();
      built = false;
    }

    private void mergeFrom(TestSummary existingSummary) {
      // Yuck, manually fill in fields.
      summary.shardRunStatuses = ArrayListMultimap.create(existingSummary.shardRunStatuses);
      setTarget(existingSummary.target);
      setStatus(existingSummary.status);
      addCoverageFiles(existingSummary.coverageFiles);
      addPassedLogs(existingSummary.passedLogs);
      addFailedLogs(existingSummary.failedLogs);

      if (existingSummary.failedTestCases != null) {
        addFailedTestCases(existingSummary.failedTestCases);
      }

      addTestTimes(existingSummary.testTimes);
      addWarnings(existingSummary.warnings);
      setActionRan(existingSummary.actionRan);
      setNumCached(existingSummary.numCached);
      setRanRemotely(existingSummary.ranRemotely);
      setWasUnreportedWrongSize(existingSummary.wasUnreportedWrongSize);
    }

    // Implements copy on write logic, allowing reuse of the same builder.
    private void checkMutation() {
      // If mutating the builder after an object was built, create another copy.
      if (built) {
        built = false;
        TestSummary lastSummary = summary;
        summary = new TestSummary();
        mergeFrom(lastSummary);
      }
    }

    // This used to return a reference to the value on success.
    // However, since it can alter the summary member, inlining it in an
    // assignment to a property of summary was unsafe.
    private void checkMutation(Object value) {
      Preconditions.checkNotNull(value);
      checkMutation();
    }

    public Builder setTarget(ConfiguredTarget target) {
      checkMutation(target);
      summary.target = target;
      return this;
    }

    public Builder setStatus(BlazeTestStatus status) {
      checkMutation(status);
      summary.status = status;
      return this;
    }

    public Builder addCoverageFiles(List<Path> coverageFiles) {
      checkMutation(coverageFiles);
      summary.coverageFiles.addAll(coverageFiles);
      return this;
    }

    public Builder addPassedLogs(List<Path> passedLogs) {
      checkMutation(passedLogs);
      summary.passedLogs.addAll(passedLogs);
      return this;
    }

    public Builder addFailedLogs(List<Path> failedLogs) {
      checkMutation(failedLogs);
      summary.failedLogs.addAll(failedLogs);
      return this;
    }

    public Builder addFailedTestCases(FailedTestCaseDetails failedTestCases) {
      checkMutation(failedTestCases);

      if (summary.failedTestCases == null) {
        summary.failedTestCases = new FailedTestCaseDetails(failedTestCases.getStatus());
      }
      summary.failedTestCases.mergeFrom(failedTestCases);
      return this;
    }

    public Builder addTestTimes(List<Long> testTimes) {
      checkMutation(testTimes);
      summary.testTimes.addAll(testTimes);
      return this;
    }

    public Builder addWarnings(List<String> warnings) {
      checkMutation(warnings);
      summary.warnings.addAll(warnings);
      return this;
    }

    public Builder setActionRan(boolean actionRan) {
      checkMutation();
      summary.actionRan = actionRan;
      return this;
    }

    public Builder setNumCached(int numCached) {
      checkMutation();
      summary.numCached = numCached;
      return this;
    }

    public Builder setNumLocalActionCached(int numLocalActionCached) {
      checkMutation();
      summary.numLocalActionCached = numLocalActionCached;
      return this;
    }

    public Builder setRanRemotely(boolean ranRemotely) {
      checkMutation();
      summary.ranRemotely = ranRemotely;
      return this;
    }

    public Builder setWasUnreportedWrongSize(boolean wasUnreportedWrongSize) {
      checkMutation();
      summary.wasUnreportedWrongSize = wasUnreportedWrongSize;
      return this;
    }

    /**
     * Records a new result for the given shard of the test.
     *
     * @return an immutable view of the statuses associated with the shard, with the new element.
     */
    public List<BlazeTestStatus> addShardStatus(int shardNumber, BlazeTestStatus status) {
      Preconditions.checkState(summary.shardRunStatuses.put(shardNumber, status),
          "shardRunStatuses must allow duplicate statuses");
      return ImmutableList.copyOf(summary.shardRunStatuses.get(shardNumber));
    }

    /**
     * Returns the created TestSummary object.
     * Any actions following a build() will create another copy of the same values.
     * Since no mutators are provided directly by TestSummary, a copy will not
     * be produced if two builds are invoked in a row without calling a setter.
     */
    public TestSummary build() {
      peek();
      if (!built) {
        makeSummaryImmutable();
        // else: it is already immutable.
      }

      Preconditions.checkState(built, "Built flag was not set");
      return summary;
    }

    /**
     * Within-package, it is possible to read directly from an
     * incompletely-built TestSummary. Used to pass Builders around directly.
     */
    TestSummary peek() {
      Preconditions.checkNotNull(summary.target, "Target cannot be null");
      Preconditions.checkNotNull(summary.status, "Status cannot be null");
      return summary;
    }

    private void makeSummaryImmutable() {
      // Once finalized, the list types are immutable.
      summary.passedLogs = Collections.unmodifiableList(summary.passedLogs);
      summary.failedLogs = Collections.unmodifiableList(summary.failedLogs);
      summary.warnings = Collections.unmodifiableList(summary.warnings);
      summary.coverageFiles = Collections.unmodifiableList(summary.coverageFiles);
      summary.testTimes = Collections.unmodifiableList(summary.testTimes);

      built = true;
    }
  }

  private ConfiguredTarget target;
  private BlazeTestStatus status;
  // Currently only populated if --runs_per_test_detects_flakes is enabled.
  private Multimap<Integer, BlazeTestStatus> shardRunStatuses = ArrayListMultimap.create();
  private int numCached;
  private int numLocalActionCached;
  private boolean actionRan;
  private boolean ranRemotely;
  private boolean wasUnreportedWrongSize;
  private FailedTestCaseDetails failedTestCases;
  private List<Path> passedLogs = new ArrayList<>();
  private List<Path> failedLogs = new ArrayList<>();
  private List<String> warnings = new ArrayList<>();
  private List<Path> coverageFiles = new ArrayList<>();
  private List<Long> testTimes = new ArrayList<>();

  // Don't allow public instantiation; go through the Builder.
  private TestSummary() {
  }

  /**
   * Creates a new Builder allowing construction of a new TestSummary object.
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Creates a new Builder initialized with a copy of the existing object's values.
   */
  public static Builder newBuilderFromExisting(TestSummary existing) {
    Builder builder = new Builder();
    builder.mergeFrom(existing);
    return builder;
  }

  public ConfiguredTarget getTarget() {
    return target;
  }

  public BlazeTestStatus getStatus() {
    return status;
  }

  public boolean isCached() {
    return numCached > 0;
  }

  public boolean isLocalActionCached() {
    return numLocalActionCached > 0;
  }

  public int numLocalActionCached() {
    return numLocalActionCached;
  }

  public int numCached() {
    return numCached;
  }

  private int numUncached() {
    return totalRuns() - numCached;
  }

  public boolean actionRan() {
    return actionRan;
  }

  public boolean ranRemotely() {
    return ranRemotely;
  }

  public boolean wasUnreportedWrongSize() {
    return wasUnreportedWrongSize;
  }

  public FailedTestCaseDetails getFailedTestCases() {
    return failedTestCases;
  }
  public List<Path> getCoverageFiles() {
    return coverageFiles;
  }

  public List<Path> getPassedLogs() {
    return passedLogs;
  }

  public List<Path> getFailedLogs() {
    return failedLogs;
  }

  /**
   * Returns an immutable view of the warnings associated with this test.
   */
  public List<String> getWarnings() {
    return Collections.unmodifiableList(warnings);
  }

  @Override
  public int compareTo(TestSummary that) {
    if (this.isCached() != that.isCached()) {
      return this.isCached() ? -1 : 1;
    } else if ((this.isCached() && that.isCached()) && (this.numUncached() != that.numUncached())) {
      return this.numUncached() - that.numUncached();
    } else if (this.status != that.status) {
      return this.status.getSortKey() - that.status.getSortKey();
    } else {
      Artifact thisExecutable = this.target.getProvider(FilesToRunProvider.class).getExecutable();
      Artifact thatExecutable = that.target.getProvider(FilesToRunProvider.class).getExecutable();
      return thisExecutable.getPath().compareTo(thatExecutable.getPath());
    }
  }

  List<Long> getTestTimes() {
    return testTimes;
  }

  public int getNumCached() {
    return numCached;
  }

  public int totalRuns() {
    return testTimes.size();
  }

  static Mode getStatusMode(BlazeTestStatus status) {
    return status == BlazeTestStatus.PASSED
        ? Mode.INFO
        : (status == BlazeTestStatus.FLAKY ? Mode.WARNING : Mode.ERROR);
  }
}