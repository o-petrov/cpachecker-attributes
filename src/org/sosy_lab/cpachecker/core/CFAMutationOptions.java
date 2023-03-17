// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2023 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.core;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.common.ShutdownManager;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.configuration.TimeSpanOption;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.common.time.TimeSpan;
import org.sosy_lab.cpachecker.cfa.CParser;
import org.sosy_lab.cpachecker.cfa.CParser.ParserOptions;
import org.sosy_lab.cpachecker.util.resources.ResourceLimit;
import org.sosy_lab.cpachecker.util.resources.ResourceLimitChecker;
import org.sosy_lab.cpachecker.util.resources.WalltimeLimit;

@Options(prefix = "cfaMutation")
final class CFAMutationOptions {

  @Option(
      secure = true,
      name = "rollbacksInRowCheck",
      description =
          "If a mutation round is unsuccessfull (i.e. sought-for bug does not occur), "
              + "the mutation is rollbacked. If this count of rollbacks occur in row, "
              + "check that rollbacked CFA produces the sought-for bug.\n"
              + "If set to 0, do not check any rollbacks.\n"
              + "If set to 1, check that bug occurs after every rollback.\n"
              + "If set to 2, check every other one that occurs immediately after "
              + "another one, so if 5 rollbacks occur in a row, 2nd and 4th "
              + "will be checked. And so on.")
  private int checkAfterRollbacks = 5;

  @Option(
      secure = true,
      name = "walltimeLimit.factor",
      description =
          "Sometimes analysis run can be unpredictably long. To run many runs successfully, "
              + "CFA mutator hard caps walltime for every run (200s by default), and also soft "
              + "caps walltime for the runs after original by multiplying used time by the factor "
              + "and adding the bias. By default soft cap is orig.time * 2.0 + 5s. Original "
              + "(first) run gets hard-cap time limit, all others get the lower between the two.")
  private double timelimitFactor = 2.0;

  @TimeSpanOption(codeUnit = TimeUnit.SECONDS, defaultUserUnit = TimeUnit.SECONDS, min = 0)
  @Option(
      secure = true,
      name = "walltimeLimit.add",
      description =
          "Sometimes analysis run can be unpredictably long. To run many runs successfully, "
              + "CFA mutator hard caps walltime for every run (200s by default), and also soft "
              + "caps walltime for the runs after original by multiplying used time by the factor "
              + "and adding the bias. By default soft cap is orig.time * 2.0 + 5s. Original "
              + "(first) run gets hard-cap time limit, all others get the lower between the two.")
  private TimeSpan timelimitBias = TimeSpan.ofSeconds(5);

  @TimeSpanOption(codeUnit = TimeUnit.SECONDS, defaultUserUnit = TimeUnit.SECONDS, min = 10)
  @Option(
      secure = true,
      name = "walltimeLimit.hardcap",
      description =
          "Sometimes analysis run can be unpredictably long. To run many runs successfully, "
              + "CFA mutator hard caps walltime for every run (200s by default), and also soft "
              + "caps walltime for the runs after original by multiplying used time by the factor "
              + "and adding the bias. By default soft cap is orig.time * 2.0 + 5s. Original "
              + "(first) run gets hard-cap time limit, all others get the lower between the two.")
  private TimeSpan timelimitCap = TimeSpan.ofSeconds(200);

  @TimeSpanOption(codeUnit = TimeUnit.SECONDS, defaultUserUnit = TimeUnit.SECONDS, min = 10)
  @Option(
      secure = true,
      name = "timeLimit.cexCheck",
      description =
          "Limit time for countrexample feasibility check. This option is used only if CFA mutations "
              + "are used to find a feasible error.")
  private TimeSpan timeForCex = TimeSpan.ofSeconds(60);

  private TimeSpan originalRun;

  private final ImmutableList<ResourceLimit> globalLimits;

  public CFAMutationOptions(Configuration pConfig, ResourceLimitChecker pLimits)
      throws InvalidConfigurationException {

    pConfig.inject(this, CFAMutationOptions.class);

    ParserOptions parserOptions = CParser.Factory.getOptions(pConfig);
    if (parserOptions.shouldCollectACSLAnnotations()) {
      throw new InvalidConfigurationException(
          "CFA mutation can not handle ACSL annotations. Do not specify "
              + "'cfaMutation=true' and 'parser.collectACSLAnnotations=true' simultaneously");
    }

    globalLimits = ImmutableList.copyOf(pLimits.getResourceLimits());
  }

  int getCheckAfterRollbacks() {
    return checkAfterRollbacks;
  }

  ImmutableList<ResourceLimit> getLimitsForAnalysis() {
    if (originalRun == null) {
      return ImmutableList.of(WalltimeLimit.fromNowOn(timelimitCap));
    }

    // choose lower of hard and soft caps
    TimeSpan lowerCap = timelimitCap;
    double softcap = originalRun.asMillis() * timelimitFactor + timelimitBias.asMillis();
    if (softcap < timelimitCap.asMillis()) {
      lowerCap = TimeSpan.ofMillis((long) softcap);
    }

    return ImmutableList.of(WalltimeLimit.fromNowOn(lowerCap));
  }

  public ResourceLimitChecker getResourceLimitCheckerForAnalysis(ShutdownManager pShutdownManager) {
    return new ResourceLimitChecker(pShutdownManager, getLimitsForAnalysis());
  }

  public ResourceLimitChecker getResourceLimitCheckerForFeasibility(
      ShutdownManager pShutdownManager) {
    ImmutableList<ResourceLimit> resourceLimits =
        ImmutableList.of(WalltimeLimit.fromNowOn(timeForCex));
    return new ResourceLimitChecker(pShutdownManager, resourceLimits);
  }

  public void setOriginalTime(TimeSpan pConsumedTime, LogManager pLogger) {
    Preconditions.checkState(originalRun == null);
    originalRun = pConsumedTime;

    pLogger.log(
        Level.INFO,
        "Using",
        Joiner.on(", ").join(Iterables.transform(getLimitsForAnalysis(), ResourceLimit::getName)),
        "for the following rounds");
  }

  public @Nullable String shouldShutdown() {
    List<ResourceLimit> nextRunLimits = getLimitsForAnalysis();
    for (ResourceLimit localLimit : nextRunLimits) {
      Class<? extends ResourceLimit> cls = localLimit.getClass();
      long localTimeout = localLimit.nanoSecondsToNextCheck(localLimit.getCurrentValue());

      for (ResourceLimit globalLimit : globalLimits) {
        if (cls.isInstance(globalLimit)
            && globalLimit.isExceeded(globalLimit.getCurrentValue() + localTimeout)) {
          return globalLimit.getName() + " will exceed during next analysis run";
        }
      }
    }

    return null;
  }
}