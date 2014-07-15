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

package com.google.devtools.build.lib.view;

import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.Attribute.ConfigurationTransition.DATA;
import static com.google.devtools.build.lib.packages.Attribute.ConfigurationTransition.HOST;
import static com.google.devtools.build.lib.packages.Type.BOOLEAN;
import static com.google.devtools.build.lib.packages.Type.DISTRIBUTIONS;
import static com.google.devtools.build.lib.packages.Type.INTEGER;
import static com.google.devtools.build.lib.packages.Type.LABEL;
import static com.google.devtools.build.lib.packages.Type.LABEL_LIST;
import static com.google.devtools.build.lib.packages.Type.LICENSE;
import static com.google.devtools.build.lib.packages.Type.NODEP_LABEL_LIST;
import static com.google.devtools.build.lib.packages.Type.STRING;
import static com.google.devtools.build.lib.packages.Type.STRING_LIST;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.Attribute.LateBoundLabel;
import com.google.devtools.build.lib.packages.Attribute.LateBoundLabelList;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.Builder;
import com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType;
import com.google.devtools.build.lib.packages.TestSize;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.syntax.Label;
import com.google.devtools.build.lib.view.config.BuildConfiguration;
import com.google.devtools.build.lib.view.config.RunUnder;

import java.util.List;

/**
 * Rule class definitions used by (almost) every rule.
 */
public class BaseRuleClasses {
  /**
   * Label of the pseudo-filegroup that contains all the targets that are needed
   * for running tests in coverage mode.
   */
  private static final Label COVERAGE_SUPPORT_LABEL =
      Label.parseAbsoluteUnchecked("//tools/defaults:coverage");


  private static final Attribute.ComputedDefault obsoleteDefault =
      new Attribute.ComputedDefault() {
        @Override
        public Object getDefault(AttributeMap rule) {
          return rule.getPackageDefaultObsolete();
        }
      };

  private static final Attribute.ComputedDefault testonlyDefault =
      new Attribute.ComputedDefault() {
        @Override
        public Object getDefault(AttributeMap rule) {
          return rule.getPackageDefaultTestOnly();
        }
      };

  private static final Attribute.ComputedDefault deprecationDefault =
      new Attribute.ComputedDefault() {
        @Override
        public Object getDefault(AttributeMap rule) {
          return rule.getPackageDefaultDeprecation();
        }
      };

  /**
   * Implementation for the :action_listener attribute.
   */
  private static final LateBoundLabelList<BuildConfiguration> ACTION_LISTENER =
      new LateBoundLabelList<BuildConfiguration>() {
    @Override
    public List<Label> getDefault(Rule rule, BuildConfiguration configuration) {
      // action_listeners are special rules; they tell the build system to add extra_actions to
      // existing rules. As such they need an edge to every ConfiguredTarget with the limitation
      // that they only run on the target configuration and should not operate on action_listeners
      // and extra_actions themselves (to avoid cycles).
      return configuration.getActionListeners();
    }
  };

  private static final LateBoundLabelList<BuildConfiguration> COVERAGE_SUPPORT =
      new LateBoundLabelList<BuildConfiguration>(ImmutableList.of(COVERAGE_SUPPORT_LABEL)) {
        @Override
        public List<Label> getDefault(Rule rule, BuildConfiguration configuration) {
          return configuration.isCodeCoverageEnabled()
              ? ImmutableList.<Label>copyOf(configuration.getCoverageLabels())
              : ImmutableList.<Label>of();
        }
      };

  /**
   * Implementation for the :run_under attribute.
   */
  private static final LateBoundLabel<BuildConfiguration> RUN_UNDER =
      new LateBoundLabel<BuildConfiguration>() {
        @Override
        public Label getDefault(Rule rule, BuildConfiguration configuration) {
          RunUnder runUnder = configuration.getRunUnder();
          return runUnder == null ? null : runUnder.getLabel();
        }
      };

  /**
   * A base rule for all test rules.
   */
  @BlazeRule(name = "$test_base_rule",
               type = RuleClassType.ABSTRACT)
  public static final class TestBaseRule implements RuleDefinition {
    @Override
    public RuleClass build(Builder builder, RuleDefinitionEnvironment env) {
      return builder
          .add(attr("size", STRING).value("medium").taggable().nonconfigurable())
          .add(attr("timeout", STRING).taggable().nonconfigurable().value(
              new Attribute.ComputedDefault() {
                @Override
                public Object getDefault(AttributeMap rule) {
                  TestSize size = TestSize.getTestSize(rule.get("size", Type.STRING));
                  if (size != null) {
                    String timeout = size.getDefaultTimeout().toString();
                    if (timeout != null) {
                      return timeout;
                    }
                  }
                  return "illegal";
                }
              }))
          .add(attr("flaky", BOOLEAN).value(false).taggable().nonconfigurable())
          .add(attr("shard_count", INTEGER).value(-1))
          .add(attr("env", STRING_LIST).value(ImmutableList.of("corp"))
               .undocumented("Deprecated").taggable().nonconfigurable())
          .add(attr("local", BOOLEAN).value(false).taggable().nonconfigurable())
          .add(attr("args", STRING_LIST).nonconfigurable())
          // Keep this in sync with BinTools.
          .add(attr("$test_tools", LABEL_LIST).cfg(HOST).value(ImmutableList.of(
              env.getLabel("//tools:test_setup_scripts"))))
          // TODO(bazel-team): TestHelper loads gcov for coverage, so all tests implicitly depend on
          // crosstool. Ugh!
          .add(attr(":coverage_support", LABEL_LIST).cfg(HOST).value(COVERAGE_SUPPORT))

          // The target itself and run_under both run on the same machine. We use the DATA config
          // here because the run_under acts like a data dependency.
          .add(attr(":run_under", LABEL).cfg(DATA).value(RUN_UNDER))
          .build();
    }
  }

  /**
   * Common parts of rules.
   */
  @BlazeRule(name = "$base_rule",
               type = RuleClassType.ABSTRACT)
  public static final class BaseRule implements RuleDefinition {
    @Override
    public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment env) {
      return builder
          // The name attribute is handled specially, so it does not appear here.
          //
          // The visibility attribute is also special: it is a nodep label, and loading the
          // necessary package groups is handled by {@link LabelVisitor#visitTargetVisibility}.
          // Package groups always have the null configuration so that they are not duplicated
          // needlessly.
          .add(attr("visibility", NODEP_LABEL_LIST).orderIndependent().nonconfigurable().cfg(HOST))
          .add(attr("tags", STRING_LIST).orderIndependent().taggable().nonconfigurable())
          .add(attr("deprecation", STRING).nonconfigurable().value(deprecationDefault))
          .add(attr("licenses", LICENSE).nonconfigurable())
          .add(attr("distribs", DISTRIBUTIONS).nonconfigurable())
          .add(attr("generator_name", STRING).undocumented("internal"))
          .add(attr("generator_function", STRING).undocumented("internal"))
          .add(attr("testonly", BOOLEAN).nonconfigurable().value(testonlyDefault))
          .add(attr("obsolete", BOOLEAN).nonconfigurable().value(obsoleteDefault))
          .add(attr(":action_listener", LABEL_LIST).cfg(HOST).value(ACTION_LISTENER))
          .build();
    }
  }

  /**
   * Common ancestor class for all rules.
   */
  @BlazeRule(name = "$rule",
               type = RuleClassType.ABSTRACT,
               ancestors = { BaseRule.class })
  public static final class RuleBase implements RuleDefinition {
    @Override
    public RuleClass build(Builder builder, RuleDefinitionEnvironment env) {
      return builder
          .add(attr("deps", LABEL_LIST))
          .add(attr("data", LABEL_LIST).cfg(DATA))
          .build();
    }
  }

}