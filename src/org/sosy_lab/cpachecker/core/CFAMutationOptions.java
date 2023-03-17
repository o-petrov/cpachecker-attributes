// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2023 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.core;

import java.util.concurrent.TimeUnit;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.configuration.TimeSpanOption;
import org.sosy_lab.common.time.TimeSpan;
import org.sosy_lab.cpachecker.cfa.CParser;
import org.sosy_lab.cpachecker.cfa.CParser.ParserOptions;

@Options(prefix = "cfaMutation")
class CFAMutationOptions {

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

  public CFAMutationOptions(Configuration pConfig) throws InvalidConfigurationException {
    pConfig.inject(this, CFAMutationOptions.class);

    ParserOptions parserOptions = CParser.Factory.getOptions(pConfig);
    if (parserOptions.shouldCollectACSLAnnotations()) {
      throw new InvalidConfigurationException(
          "CFA mutation can not handle ACSL annotations. Do not specify "
              + "'cfaMutation=true' and 'parser.collectACSLAnnotations=true' simultaneously");
    }
  }

  public int getCheckAfterRollbacks() {
    return checkAfterRollbacks;
  }

  public double getTimelimitFactor() {
    return timelimitFactor;
  }

  public TimeSpan getTimelimitBias() {
    return timelimitBias;
  }

  public TimeSpan getTimelimitCap() {
    return timelimitCap;
  }

  public TimeSpan getTimeForCex() {
    return timeForCex;
  }
}