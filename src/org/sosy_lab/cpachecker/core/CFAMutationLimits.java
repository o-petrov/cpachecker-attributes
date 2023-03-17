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
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import javax.management.JMException;
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
import org.sosy_lab.cpachecker.util.resources.ProcessCpuTime;
import org.sosy_lab.cpachecker.util.resources.ProcessCpuTimeLimit;
import org.sosy_lab.cpachecker.util.resources.ResourceLimit;
import org.sosy_lab.cpachecker.util.resources.ResourceLimitChecker;
import org.sosy_lab.cpachecker.util.resources.ThreadCpuTimeLimit;
import org.sosy_lab.cpachecker.util.resources.WalltimeLimit;

@Options(prefix = "cfaMutation")
final class CFAMutationLimits {

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
      name = "timelimit.factor",
      description =
          "Sometimes analysis run can be unpredictably long. To run many runs successfully, "
              + "CFA mutator hard caps time for every run (200s by default), and also soft "
              + "caps time for the runs after original by multiplying used time by the factor "
              + "and adding the bias. By default soft cap is orig.time * 2.0 + 5s. Original "
              + "(first) run gets hardcap time limit, all others get the lower between the two."
              + "For every global timelimit specified for whole mutation process, "
              + "a round timelimit of the same type will be produced."
              + "(E.g., if both cpu and wall time limit specified, every analysis round gets "
              + "cpu and wall time limits with same time span.)")
  private double timelimitFactor = 2.0;

  @TimeSpanOption(codeUnit = TimeUnit.SECONDS, defaultUserUnit = TimeUnit.SECONDS, min = 0)
  @Option(
      secure = true,
      name = "timelimit.add",
      description =
          "Sometimes analysis run can be unpredictably long. To run many runs successfully, "
              + "CFA mutator hard caps time for every run (200s by default), and also soft "
              + "caps time for the runs after original by multiplying used time by the factor "
              + "and adding the bias. By default soft cap is orig.time * 2.0 + 5s. Original "
              + "(first) run gets hardcap time limit, all others get the lower between the two."
              + "For every global timelimit specified for whole mutation process, "
              + "a round timelimit of the same type will be produced."
              + "(E.g., if both cpu and wall time limit specified, every analysis round gets "
              + "cpu and wall time limits with same time span.)")
  private TimeSpan timelimitBias = TimeSpan.ofSeconds(5);

  @TimeSpanOption(codeUnit = TimeUnit.SECONDS, defaultUserUnit = TimeUnit.SECONDS, min = 10)
  @Option(
      secure = true,
      name = "timelimit.hardcap",
      description =
          "Sometimes analysis run can be unpredictably long. To run many runs successfully, "
              + "CFA mutator hard caps time for every run (200s by default), and also soft "
              + "caps time for the runs after original by multiplying used time by the factor "
              + "and adding the bias. By default soft cap is orig.time * 2.0 + 5s. Original "
              + "(first) run gets hardcap time limit, all others get the lower between the two."
              + "For every global timelimit specified for whole mutation process, "
              + "a round timelimit of the same type will be produced."
              + "(E.g., if both cpu and wall time limit specified, every analysis round gets "
              + "cpu and wall time limits with same time span.)")
  private TimeSpan hardcap = TimeSpan.ofSeconds(200);

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

  private final LogManager logger;

  private final ShutdownManager shutdownManager;

  public CFAMutationLimits(
      Configuration pConfig,
      LogManager pLogger,
      ShutdownManager pShutdownManager,
      ResourceLimitChecker pLimits)
      throws InvalidConfigurationException {

    logger = Preconditions.checkNotNull(pLogger);
    shutdownManager = Preconditions.checkNotNull(pShutdownManager);

    pConfig.inject(this, CFAMutationLimits.class);

    ParserOptions parserOptions = CParser.Factory.getOptions(pConfig);
    if (parserOptions.shouldCollectACSLAnnotations()) {
      throw new InvalidConfigurationException(
          "CFA mutation can not handle ACSL annotations. Do not specify "
              + "'cfaMutation=true' and 'parser.collectACSLAnnotations=true' simultaneously");
    }

    ImmutableList.Builder<ResourceLimit> relevantLimits = ImmutableList.builder();
    for (ResourceLimit limit : pLimits.getResourceLimits()) {
      if (limit instanceof ProcessCpuTimeLimit) {
        try {
          ProcessCpuTime.read();
          relevantLimits.add(limit);
        } catch (JMException e) {
          logger.logDebugException(e, "filtering out irrelevant limits for CFA mutation");
        }

      } else if (limit instanceof ThreadCpuTimeLimit) {
        relevantLimits.add(limit);

      } else if (limit instanceof WalltimeLimit) {
        relevantLimits.add(limit);

      } else {
        logger.log(
            Level.WARNING,
            "Unknown type of resource limit",
            limit,
            "will be ignored while constructing resource limits",
            "for analysis rounds and feasibility check");
      }
    }

    globalLimits = relevantLimits.build();
    if (globalLimits.isEmpty()) {
      logger.log(
          Level.WARNING,
          "No resource limits will be used for analysis rounds and feasibility check");
    }
  }

  int getCheckAfterRollbacks() {
    return checkAfterRollbacks;
  }

  private ImmutableList<ResourceLimit> produceLimitsFromNowOn(TimeSpan pTime) {
    ImmutableList.Builder<ResourceLimit> result = ImmutableList.builder();
    for (ResourceLimit globalLimit : globalLimits) {
      if (globalLimit instanceof ProcessCpuTimeLimit) {
        try {
          result.add(ProcessCpuTimeLimit.fromNowOn(pTime));
        } catch (JMException e) {
          throw new AssertionError(e);
        }

      } else if (globalLimit instanceof ThreadCpuTimeLimit) {
        result.add(ThreadCpuTimeLimit.fromNowOn(pTime, Thread.currentThread()));

      } else if (globalLimit instanceof WalltimeLimit) {
        result.add(WalltimeLimit.fromNowOn(pTime));

      } else {
        throw new AssertionError(
            "unexpeted " + globalLimit.getClass() + " class of time limit " + globalLimit);
      }
    }
    return result.build();
  }

  private ImmutableList<ResourceLimit> getLimitsForAnalysis() {
    if (originalRun == null) {
      return produceLimitsFromNowOn(hardcap);
    }

    // choose lower of hard and soft caps
    TimeSpan lowerCap = hardcap;
    double softcap = originalRun.asMillis() * timelimitFactor + timelimitBias.asMillis();
    if (softcap < hardcap.asMillis()) {
      lowerCap = TimeSpan.ofMillis((long) softcap);
    }

    return produceLimitsFromNowOn(lowerCap);
  }

  public ResourceLimitChecker getResourceLimitCheckerForAnalysis(ShutdownManager pShutdownManager) {
    return new ResourceLimitChecker(pShutdownManager, getLimitsForAnalysis());
  }

  public ResourceLimitChecker getResourceLimitCheckerForFeasibility(
      ShutdownManager pShutdownManager) {
    return new ResourceLimitChecker(pShutdownManager, produceLimitsFromNowOn(timeForCex));
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

  public boolean exceedsLimitsForAnalysis(String pDescription) {
    return shouldShutdown(
        getLimitsForAnalysis(), "will exceed during next analysis run", pDescription);
  }

  public boolean exceedsLimitsForFeasibility() {
    return shouldShutdown(
        produceLimitsFromNowOn(timeForCex),
        "will exceed during next feasibility check",
        "CFA mutation does not have enough time to check last error");
  }

  private boolean shouldShutdown(
      ImmutableList<ResourceLimit> pLocalLimits, String pWillExceed, String pDescription) {
    if (shutdownManager.getNotifier().shouldShutdown()) {
      logger.log(Level.INFO, pDescription, shutdownManager.getNotifier().getReason());
      return true;
    }

    for (int i = 0; i < pLocalLimits.size(); i++) {
      ResourceLimit localLimit = pLocalLimits.get(i);
      ResourceLimit globalLimit = globalLimits.get(i);
      assert globalLimit.getClass().isInstance(localLimit) : "limits dont match";

      long localTimeout =
          localLimit.nanoSecondsToNextCheck(localLimit.getCurrentValue())
              + TimeSpan.ofSeconds(1).asNanos(); // plus 1s for my code before and after analysis

      if (globalLimit.isExceeded(globalLimit.getCurrentValue() + localTimeout)) {
        logger.log(Level.INFO, pDescription, globalLimit.getName(), pWillExceed);
        return true;
      }
    }

    return false;
  }
}