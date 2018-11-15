/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2014  Dirk Beyer
 *  All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 *  CPAchecker web page:
 *    http://cpachecker.sosy-lab.org
 */
package org.sosy_lab.cpachecker.core.algorithm.mpv;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import org.sosy_lab.common.ShutdownManager;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.ClassOption;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.ConfigurationBuilder;
import org.sosy_lab.common.configuration.FileOption;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.configuration.TimeSpanOption;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.common.time.TimeSpan;
import org.sosy_lab.common.time.Timer;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.core.CPAcheckerResult.Result;
import org.sosy_lab.cpachecker.core.CoreComponentsFactory;
import org.sosy_lab.cpachecker.core.Specification;
import org.sosy_lab.cpachecker.core.algorithm.Algorithm;
import org.sosy_lab.cpachecker.core.algorithm.mpv.partition.Partition;
import org.sosy_lab.cpachecker.core.algorithm.mpv.partition.PartitioningOperator;
import org.sosy_lab.cpachecker.core.algorithm.mpv.partition.SeparatePartitioningOperator;
import org.sosy_lab.cpachecker.core.algorithm.mpv.property.AbstractSingleProperty;
import org.sosy_lab.cpachecker.core.algorithm.mpv.property.MultipleProperties;
import org.sosy_lab.cpachecker.core.algorithm.mpv.property.MultipleProperties.PropertySeparator;
import org.sosy_lab.cpachecker.core.interfaces.AbstractState;
import org.sosy_lab.cpachecker.core.interfaces.ConfigurableProgramAnalysis;
import org.sosy_lab.cpachecker.core.interfaces.Precision;
import org.sosy_lab.cpachecker.core.interfaces.StateSpacePartition;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;
import org.sosy_lab.cpachecker.core.interfaces.StatisticsProvider;
import org.sosy_lab.cpachecker.core.reachedset.AggregatedReachedSets;
import org.sosy_lab.cpachecker.core.reachedset.ReachedSet;
import org.sosy_lab.cpachecker.core.reachedset.UnmodifiableReachedSet;
import org.sosy_lab.cpachecker.exceptions.CPAException;
import org.sosy_lab.cpachecker.util.AbstractStates;
import org.sosy_lab.cpachecker.util.resources.ResourceLimitChecker;

@Options(prefix = "mpv")
public class MPVAlgorithm implements Algorithm, StatisticsProvider {

  public static class MPVStatistics implements Statistics {

    private final Timer totalTimer = new Timer();
    private final Timer createPartitionsTimer = new Timer();

    private int iterationNumber;
    private final List<Partition> partitions;
    private final MultipleProperties multipleProperties;

    private Collection<Statistics> statistics;

    private MPVStatistics(final MultipleProperties pMultipleProperties) {
      multipleProperties = pMultipleProperties;
      iterationNumber = 0;
      partitions = Lists.newArrayList();
      statistics = Lists.newArrayList();
    }

    @Override
    public String getName() {
      return "MPV algorithm";
    }

    private TimeSpan getCurrentCpuTime() {
      TimeSpan totalCpuTime = TimeSpan.ofNanos(0);
      for (AbstractSingleProperty property : multipleProperties.getProperties()) {
        totalCpuTime = TimeSpan.sum(totalCpuTime, property.getCpuTime());
      }
      return totalCpuTime;
    }

    @Override
    public void printStatistics(PrintStream out, Result pResult, UnmodifiableReachedSet pReached) {
      TimeSpan totalCpuTime = getCurrentCpuTime();
      out.println("Number of iterations:                         " + iterationNumber);
      out.println(
          "Total wall time for creating partitions:  "
              + createPartitionsTimer.getSumTime().formatAs(TimeUnit.SECONDS));
      out.println(
          "Total wall time for MPV algorithm:        "
              + totalTimer.getSumTime().formatAs(TimeUnit.SECONDS));
      out.println(
          "Total CPU time for MPV algorithm:         " + totalCpuTime.formatAs(TimeUnit.SECONDS));
      out.println();
      out.println("Partitions statistics:");
      int counter = 1;
      for (Partition partition : partitions) {
        out.println("Partition " + counter++ + ":");
        out.println("  Properties (" + partition.getNumberOfProperties() + "): " + partition);
        out.println("  CPU time limit:\t" + partition.getTimeLimit().formatAs(TimeUnit.SECONDS));
        out.println("  Spent CPU time:\t" + partition.getSpentCPUTime().formatAs(TimeUnit.SECONDS));
      }
      out.println();
      out.println("Properties statistics:");
      for (AbstractSingleProperty property : multipleProperties.getProperties()) {
        Result result = property.getResult();
        out.println("Property '" + property + "'");
        out.println("  CPU time:" + property.getCpuTime().formatAs(TimeUnit.SECONDS));
        out.println("  Relevant:    " + property.isRelevant());
        out.println("  Result:      " + result);
        if (result.equals(Result.FALSE)) {
          out.println("    Found violations:     " + property.getViolations());
          out.println("    All violations found: " + property.isAllViolationsFound());
          out.println("    Description:          " + property.getViolatedPropertyDescription());
        }
        if ((result.equals(Result.FALSE) && !property.isAllViolationsFound())
            || result.equals(Result.UNKNOWN)) {
          out.println("    Reason of UNKNOWN:    " + property.getReasonOfUnknown());
        }
      }
    }
  }

  private enum LimitAdjustmentStrategy {
    NONE,
    DISTRIBUTE_REMAINING,
    DISTRIBUTE_BY_PROPERTY
  }

  @Option(
      secure = true,
      name = "limits.cpuTimePerProperty",
      description =
          "Set CPU time limit per each property in multi-property verification "
              + "(use seconds or specify a unit; -1 to disable)")
  @TimeSpanOption(codeUnit = TimeUnit.NANOSECONDS, defaultUserUnit = TimeUnit.SECONDS, min = -1)
  private TimeSpan cpuTimePerProperty = TimeSpan.ofNanos(-1);

  @Option(
      secure = true,
      name = "limits.adjustmentStrategy",
      description =
          "Adjust resource limitations during the analysis.\n"
              + "- NONE: do not adjust resource limitations (default).\n"
              + "- DISTRIBUTE_REMAINING: distribute resources, which were allocated for some already checked "
              + "property, but were not fully spent, between other properties, which are still checking.\n"
              + "- DISTRIBUTE_BY_PROPERTY: scale resources for each property in accordance with the given "
              + "ratio in the property distribution file.")
  private LimitAdjustmentStrategy limitsAdjustmentStrategy = LimitAdjustmentStrategy.NONE;

  @Option(
      secure = true,
      name = "limits.firstPartitionRatio",
      description =
          "Change resource limitations for the first partition by the given ratio. "
              + "This option will be ignored if NONE limits adjustment strategy is used.")
  private double firstPartitionFactor = 1.0;

  @Option(
      secure = true,
      name = "limits.propertyDistributionFile",
      description =
          "Get a resource limitation distribution per property from file. "
              + "This option should be used only together with DISTRIBUTE_BY_PROPERTY limits adjustment strategy. "
              + "The following format should be used in the file:\n'<property name>':<ratio>")
  @FileOption(FileOption.Type.OPTIONAL_INPUT_FILE)
  @Nullable
  private Path propertyDistributionFile = null;

  private final Map<AbstractSingleProperty, Double> propertyDistribution;

  @Option(
      secure = true,
      name = "propertySeparator",
      description =
          "Specifies how to separate a single property.\n"
              + "- FILE: each .spc file represent a single property (i.e., property is represented by several automata).\n"
              + "- AUTOMATON: each automaton represent a single property.")
  private PropertySeparator propertySeparator = PropertySeparator.FILE;

  @Option(
      secure = true,
      name = "partitionOperator",
      description = "Partitioning operator for multi-property verification.")
  @ClassOption(packagePrefix = "org.sosy_lab.cpachecker.core.algorithm.mpv.partition")
  @Nonnull
  private PartitioningOperator.Factory partitioningOperatorFactory =
      (pConfig, pLogger, pShutdownNotifier, pProperties, pCpuTimePerProperty) ->
          new SeparatePartitioningOperator(pProperties, pCpuTimePerProperty);

  @Option(
      secure = true,
      name = "findAllViolations",
      description = "Find all violations of each checked property.")
  private boolean findAllViolations = false;

  @Option(
      secure = true,
      name = "collectAllStatistics",
      description = "Collect statistics for all inner algorithms on each iteration.")
  private boolean collectAllStatistics = false;

  private final MPVStatistics stats;
  private final ConfigurableProgramAnalysis cpa;
  private final LogManager logger;
  private final Configuration config;
  private final ShutdownNotifier shutdownNotifier;
  private final Specification specification;
  private final CFA cfa;
  private final PartitioningOperator partitioningOperator;

  public MPVAlgorithm(
      ConfigurableProgramAnalysis pCpa,
      Configuration pConfig,
      LogManager pLogger,
      ShutdownNotifier pShutdownNotifier,
      Specification pSpecification,
      CFA pCfa)
      throws InvalidConfigurationException {
    cpa = pCpa;
    config = pConfig;
    logger = pLogger;
    shutdownNotifier = pShutdownNotifier;
    specification = pSpecification;
    cfa = pCfa;
    config.inject(this);
    MultipleProperties multipleProperties =
        new MultipleProperties(
            specification.getPathToSpecificationAutomata(), propertySeparator, findAllViolations);
    multipleProperties.determineRelevance(pCfa);

    stats = new MPVStatistics(multipleProperties);
    propertyDistribution = initializePropertyDistribution();
    partitioningOperator =
        partitioningOperatorFactory.create(
            config, logger, shutdownNotifier, stats.multipleProperties, cpuTimePerProperty);
  }

  private Map<AbstractSingleProperty, Double> initializePropertyDistribution() {
    if (propertyDistributionFile == null) {
      return ImmutableMap.of();
    }
    try {
      final Pattern propertyDistributionPattern = Pattern.compile("'(.+)'\\s*:\\s*(\\S+)");
      List<String> lines = Files.readAllLines(propertyDistributionFile, Charset.defaultCharset());
      ImmutableMap.Builder<AbstractSingleProperty, Double> propertyDistributionBuilder =
          ImmutableMap.builder();
      for (String line : lines) {
        line = line.trim();
        if (line.isEmpty()) {
          // ignore empty lines
          continue;
        }
        Matcher matcher = propertyDistributionPattern.matcher(line);
        if (matcher.matches()) {
          String propertyName = matcher.group(1);
          String ratioStr = matcher.group(2);
          AbstractSingleProperty targetProperty = null;
          // attempt to find property with this name
          for (AbstractSingleProperty property : stats.multipleProperties.getProperties()) {
            if (property.getName().equals(propertyName)) {
              targetProperty = property;
              break;
            }
          }
          if (targetProperty == null) {
            logger.log(
                Level.WARNING,
                "Property with name '"
                    + propertyName
                    + "', specified in property distribution file, does not exist");
            continue;
          }
          // attempt to parse ratio
          try {
            Double ratio = Double.parseDouble(ratioStr);
            propertyDistributionBuilder.put(targetProperty, ratio);
          } catch (NumberFormatException e) {
            logger.log(
                Level.WARNING,
                "Could not parse ratio '" + ratioStr + "' for property " + propertyName);
          }
        } else {
          logger.log(
              Level.WARNING,
              "Could not parse line '"
                  + line
                  + "' in property distribution file. "
                  + "Correct format is '<property name>':<ratio>");
        }
      }
      return propertyDistributionBuilder.build();
    } catch (IOException e) {
      logger.log(
          Level.WARNING,
          e,
          "Could not read properties distribution from file " + propertyDistributionFile);
    }
    return ImmutableMap.of();
  }

  @Override
  public AlgorithmStatus run(ReachedSet reached) throws CPAException, InterruptedException {

    assert reached instanceof MPVReachedSet;
    ((MPVReachedSet) reached).setMultipleProperties(stats.multipleProperties);

    AlgorithmStatus status = AlgorithmStatus.SOUND_AND_PRECISE;

    stats.totalTimer.start();

    Iterable<CFANode> initialNodes = AbstractStates.extractLocations(reached.getFirstState());
    CFANode mainFunction = Iterables.getOnlyElement(initialNodes);

    try {
      do {
        ImmutableList<Partition> partitions = partitioningOperator.createPartition();
        int partitionNumber = 0;
        for (Partition partition : partitions) {
          int numberOfProperties = partition.getNumberOfProperties();
          if (numberOfProperties <= 0) {
            // shortcut
            continue;
          }
          stats.partitions.add(partition);
          adjustTimeLimit(partition, partitions.size(), partitionNumber);
          partitionNumber++;
          ShutdownManager shutdownManager = ShutdownManager.createWithParent(shutdownNotifier);
          ResourceLimitChecker limits =
              ResourceLimitChecker.createCpuTimeLimitChecker(
                  logger, shutdownManager, partition.getTimeLimit());
          limits.start();

          // inner algorithm
          Algorithm algorithm = createInnerAlgorithm(reached, mainFunction, shutdownManager);
          stats.multipleProperties.setTargetProperties(partition.getProperties(), reached);
          collectStatistics(algorithm);
          try {
            partition.startAnalysis();
            logger.log(
                Level.INFO,
                "Iteration "
                    + stats.iterationNumber
                    + ": checking partition "
                    + partition
                    + " with "
                    + numberOfProperties
                    + " properties");
            do {
              status = status.update(algorithm.run(reached));
            } while (!partition.isChecked(reached));
            logger.log(Level.INFO, "Stopping iteration " + stats.iterationNumber);
          } catch (InterruptedException e) {
            if (shutdownNotifier.shouldShutdown()) {
              // Interrupted by outer limit checker or by user
              logger.logUserException(Level.WARNING, e, "Analysis interrupted from the outside");
              partition.stopAnalysisOnFailure(reached, "Interrupted");
              throw e;
            } else {
              // Interrupted by inner limit checker
              logger.log(Level.INFO, e, "Partition has exhausted resource limitations");
              partition.stopAnalysisOnFailure(reached, "Inner time limit");
            }
          } catch (Exception e) {
            // Try to intercept any exception, which may be related to checking of specific
            // property, so it would be possible to successfully check other properties.
            logger.log(Level.WARNING, e, ": Exception during partition checking");
            partition.stopAnalysisOnFailure(reached, e.getClass().getSimpleName());
          } finally {
            limits.cancel();
          }
        }
      } while (!stats.multipleProperties.isChecked());
    } finally {
      stats.totalTimer.stop();
    }
    return status;
  }

  private void adjustTimeLimit(
      Partition partition, int overallPartitions, int currentPartitionNumber) {
    if (limitsAdjustmentStrategy.equals(LimitAdjustmentStrategy.NONE)) {
      // do not change the specified time limit
      return;
    }
    if (!partition.isIntermediateStep()) {
      // ignore intermediate steps
      return;
    }
    TimeSpan overallSpentCpuTime = stats.getCurrentCpuTime();
    TimeSpan overallCpuTimeLimit =
        cpuTimePerProperty.multiply(stats.multipleProperties.getNumberOfProperties());
    if (overallCpuTimeLimit.compareTo(overallSpentCpuTime) <= 0
        || overallPartitions <= currentPartitionNumber) {
      // do nothing in case of bad args - should be unreachable
      return;
    }
    TimeSpan adjustedTimeLimit = cpuTimePerProperty;
    switch (limitsAdjustmentStrategy) {
      case DISTRIBUTE_REMAINING:
        adjustedTimeLimit =
            TimeSpan.difference(overallCpuTimeLimit, overallSpentCpuTime)
                .divide(overallPartitions - currentPartitionNumber);
        break;
      case DISTRIBUTE_BY_PROPERTY:
        if (partition.getNumberOfProperties() == 1) {
          AbstractSingleProperty currentProperty = partition.getProperties().getProperties().get(0);
          if (propertyDistribution.containsKey(currentProperty)) {
            adjustedTimeLimit =
                TimeSpan.ofMillis(
                    Math.round(
                        propertyDistribution.get(currentProperty) * adjustedTimeLimit.asMillis()));
          }
        }
        break;
      default:
        break;
    }
    if (currentPartitionNumber == 0) {
      adjustedTimeLimit =
          TimeSpan.ofMillis(Math.round(firstPartitionFactor * adjustedTimeLimit.asMillis()));
    }
    partition.updateTimeLimit(adjustedTimeLimit);
  }

  private void collectStatistics(Algorithm pAlgorithm) {
    if (collectAllStatistics) {
      if (pAlgorithm instanceof StatisticsProvider) {
        ((StatisticsProvider) pAlgorithm).collectStatistics(stats.statistics);
      }
    }
  }

  private Algorithm createInnerAlgorithm(
      ReachedSet reached, CFANode mainFunction, ShutdownManager shutdownManager)
      throws InterruptedException, CPAException {
    try {
      stats.createPartitionsTimer.start();
      if (stats.iterationNumber > 0) {
        // Clear reached set for further iterations.
        reached.clear();
        AbstractState initialState =
            cpa.getInitialState(mainFunction, StateSpacePartition.getDefaultPartition());
        Precision initialPrecision =
            cpa.getInitialPrecision(mainFunction, StateSpacePartition.getDefaultPartition());
        reached.add(initialState, initialPrecision);
      }
      stats.iterationNumber++;

      ConfigurationBuilder innerConfigBuilder = Configuration.builder();
      innerConfigBuilder.copyFrom(config);
      innerConfigBuilder.clearOption("analysis.algorithm.MPV"); // to prevent infinite recursion
      Configuration singleConfig = innerConfigBuilder.build();
      CoreComponentsFactory coreComponents =
          new CoreComponentsFactory(
              singleConfig, logger, shutdownManager.getNotifier(), new AggregatedReachedSets());

      return coreComponents.createAlgorithm(cpa, cfa, specification);
    } catch (InvalidConfigurationException e) {
      // should be unreachable, since configuration is already checked
      throw new CPAException("Cannot create configuration for inner algorithm: " + e);
    } finally {
      stats.createPartitionsTimer.stop();
    }
  }

  @Override
  public void collectStatistics(Collection<Statistics> pStatsCollection) {
    pStatsCollection.add(stats);
    stats.statistics = pStatsCollection;
  }
}
