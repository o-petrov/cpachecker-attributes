/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2018  Dirk Beyer
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
package org.sosy_lab.cpachecker.cpa.predicate;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.sosy_lab.cpachecker.util.statistics.StatisticsUtils.div;
import static org.sosy_lab.cpachecker.util.statistics.StatisticsUtils.toPercent;
import static org.sosy_lab.cpachecker.util.statistics.StatisticsUtils.valueWithPercentage;

import com.google.common.base.Preconditions;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Level;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.FileOption;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.io.IO;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.common.time.TimeSpan;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.core.CPAcheckerResult.Result;
import org.sosy_lab.cpachecker.core.interfaces.Precision;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;
import org.sosy_lab.cpachecker.core.reachedset.UnmodifiableReachedSet;
import org.sosy_lab.cpachecker.cpa.predicate.persistence.LoopInvariantsWriter;
import org.sosy_lab.cpachecker.cpa.predicate.persistence.PredicateAbstractionsWriter;
import org.sosy_lab.cpachecker.cpa.predicate.persistence.PredicateMapWriter;
import org.sosy_lab.cpachecker.util.Precisions;
import org.sosy_lab.cpachecker.util.predicates.AbstractionManager;
import org.sosy_lab.cpachecker.util.predicates.AbstractionPredicate;
import org.sosy_lab.cpachecker.util.predicates.BlockOperator;
import org.sosy_lab.cpachecker.util.predicates.pathformula.PathFormulaManager;
import org.sosy_lab.cpachecker.util.predicates.regions.RegionManager;
import org.sosy_lab.cpachecker.util.predicates.smt.FormulaManagerView;
import org.sosy_lab.cpachecker.util.predicates.smt.Solver;

@Options(prefix = "cpa.predicate")
class PredicateCPAStatistics implements Statistics {

  @Option(secure=true, description="generate statistics about precisions (may be slow)")
  private boolean precisionStatistics = true;

  @Option(secure=true, description="export final predicate map",
          name="predmap.export")
  private boolean exportPredmap = true;

  @Option(secure=true, description="file for exporting final predicate map",
          name="predmap.file")
  @FileOption(FileOption.Type.OUTPUT_FILE)
  private Path predmapFile = Paths.get("predmap.txt");

  @Option(secure=true, name="precondition.file", description="File for exporting the weakest precondition.")
  @FileOption(FileOption.Type.OUTPUT_FILE)
  private Path preconditionFile = Paths.get("precondition.txt");
  @Option(secure=true, name="precondition.export", description="Export the weakest precondition?")
  private boolean preconditionExport = false;

  @Option(secure=true, description="export final loop invariants",
          name="invariants.export")
  private boolean exportInvariants = true;

  @Option(secure=true, description="export invariants as precision file?",
      name="invariants.exportAsPrecision")
  private boolean exportInvariantsAsPrecision = true;

  @Option(secure=true, description="file for exporting final loop invariants",
          name="invariants.file")
  @FileOption(FileOption.Type.OUTPUT_FILE)
  private Path invariantsFile = Paths.get("invariants.txt");

  @Option(secure=true, description="file for precision that consists of invariants.",
          name="invariants.precisionFile")
  @FileOption(FileOption.Type.OUTPUT_FILE)
  private Path invariantPrecisionsFile = Paths.get("invariantPrecs.txt");

  @Option(description="Export one abstraction formula for each abstraction state into a file?",
      name="abstractions.export")
  private boolean abstractionsExport = true;
  @Option(secure=true, description="file that consists of one abstraction formula for each abstraction state",
      name="abstractions.file")
  @FileOption(FileOption.Type.OUTPUT_FILE)
  private Path abstractionsFile = Paths.get("abstractions.txt");

  @Option(description="enable export of all relations that were collected to synthecise the abstract precision?",
      name="relations.export")
  private boolean relationsExport = false;
  @Option(description="file that consists all relations that were collected to synthecise the abstract precision",
      name="relations.file")
  @FileOption(FileOption.Type.OUTPUT_FILE)
  private Path relationsFile = Paths.get("relations.txt");

  private final LogManager logger;

  private final Solver solver;
  private final PathFormulaManager pfmgr;
  private final BlockOperator blk;
  private final RegionManager rmgr;
  private final AbstractionManager absmgr;
  private final PredicateAbstractionManager amgr;

  private final PredicateStatistics statistics;
  private final PredicateMapWriter precisionWriter;
  private final LoopInvariantsWriter loopInvariantsWriter;
  private final PredicateAbstractionsWriter abstractionsWriter;

  public PredicateCPAStatistics(
      Configuration pConfig,
      LogManager pLogger,
      CFA pCfa,
      Solver pSolver,
      PathFormulaManager pPfmgr,
      BlockOperator pBlk,
      RegionManager pRmgr,
      AbstractionManager pAbsmgr,
      PredicateAbstractionManager pPredAbsMgr,
      PredicateStatistics pStatistics)
      throws InvalidConfigurationException {
    pConfig.inject(this, PredicateCPAStatistics.class);

    logger = pLogger;
    solver = pSolver;
    pfmgr = pPfmgr;
    blk = pBlk;
    rmgr = pRmgr;
    absmgr = pAbsmgr;
    amgr = pPredAbsMgr;
    statistics = pStatistics;

    FormulaManagerView fmgr = pSolver.getFormulaManager();
    loopInvariantsWriter = new LoopInvariantsWriter(pCfa, pLogger, pAbsmgr, fmgr, pRmgr);
    abstractionsWriter = new PredicateAbstractionsWriter(pLogger, fmgr);

    if (exportPredmap && predmapFile != null) {
      precisionWriter = new PredicateMapWriter(pConfig, fmgr);
    } else {
      precisionWriter = null;
    }
  }

  @Override
  public String getName() {
    return "PredicateCPA";
  }

  /**
   * TreeMap to sort output for the user and sets for no duplication.
   */
  private static class MutablePredicateSets {

    private final SetMultimap<PredicatePrecision.LocationInstance, AbstractionPredicate>
        locationInstance;
    private final SetMultimap<CFANode, AbstractionPredicate> location;
    private final SetMultimap<String, AbstractionPredicate> function;
    private final Set<AbstractionPredicate> global;

    private MutablePredicateSets() {
      // Use special multimaps with set-semantics and an ordering only on keys (not on values)
      this.locationInstance = MultimapBuilder.treeKeys().linkedHashSetValues().build();

      this.location = MultimapBuilder.treeKeys().linkedHashSetValues().build();
      this.function = MultimapBuilder.treeKeys().linkedHashSetValues().build();
      this.global = new LinkedHashSet<>();
    }

  }

  private void exportPredmapToFile(Path targetFile, MutablePredicateSets predicates) {
    Preconditions.checkNotNull(targetFile);
    Preconditions.checkNotNull(predicates);

    Set<AbstractionPredicate> allPredicates = Sets.newLinkedHashSet(predicates.global);
    allPredicates.addAll(predicates.function.values());
    allPredicates.addAll(predicates.location.values());
    allPredicates.addAll(predicates.locationInstance.values());

    try (Writer w = IO.openOutputFile(targetFile, Charset.defaultCharset())) {
      precisionWriter.writePredicateMap(predicates.locationInstance,
          predicates.location, predicates.function, predicates.global,
          allPredicates, w);
    } catch (IOException e) {
      logger.logUserException(Level.WARNING, e, "Could not write predicate map to file");
    }
  }


  @Override
  public void printStatistics(PrintStream out, Result result, UnmodifiableReachedSet reached) {
    int maxPredsPerLocation = -1;
    int allLocs = -1;
    int avgPredsPerLocation = -1;
    if (precisionStatistics) {
      MutablePredicateSets predicates = new MutablePredicateSets();
      {
        Set<Precision> seenPrecisions = Collections.newSetFromMap(new IdentityHashMap<>());

        for (Precision precision : reached.getPrecisions()) {
          PredicatePrecision preds =
              Precisions.extractPrecisionByType(precision, PredicatePrecision.class);
          if (preds != null && seenPrecisions.add(preds)) {
            predicates.locationInstance.putAll(preds.getLocationInstancePredicates());
            predicates.location.putAll(preds.getLocalPredicates());
            predicates.function.putAll(preds.getFunctionPredicates());
            predicates.global.addAll(preds.getGlobalPredicates());
          }
        }
      }

      // check if/where to dump the predicate map
      if (exportPredmap && predmapFile != null) {
        exportPredmapToFile(predmapFile, predicates);
      }

      maxPredsPerLocation = 0;
      for (Collection<AbstractionPredicate> p : predicates.location.asMap().values()) {
        maxPredsPerLocation = Math.max(maxPredsPerLocation, p.size());
      }

      allLocs = predicates.location.keySet().size();
      int totPredsUsed = predicates.location.size();
      avgPredsPerLocation = allLocs > 0 ? totPredsUsed/allLocs : 0;
    }

    int allDistinctPreds = absmgr.getNumberOfPredicates();

    if (exportInvariants && invariantsFile != null) {
      loopInvariantsWriter.exportLoopInvariants(invariantsFile, reached);
    }

    if (abstractionsExport && abstractionsFile != null) {
      abstractionsWriter.writeAbstractions(abstractionsFile, reached);
    }

    if (exportInvariantsAsPrecision && invariantPrecisionsFile != null) {
      loopInvariantsWriter.exportLoopInvariantsAsPrecision(invariantPrecisionsFile, reached);
    }

    PredicateAbstractionManager.Stats as = amgr.stats;

    int numAbstractions = statistics.numAbstractions.getUpdateCount();
    out.println("Number of abstractions:            " + numAbstractions + " (" + toPercent(numAbstractions, statistics.postTimer.getNumberOfIntervals()) + " of all post computations)");
    if (numAbstractions > 0) {
      out.println("  Times abstraction was reused:    " + as.numAbstractionReuses);
      out.println("  Because of function entry/exit:  " + valueWithPercentage(blk.numBlkFunctions.getValue(), numAbstractions));
      out.println("  Because of loop head:            " + valueWithPercentage(blk.numBlkLoops.getValue(), numAbstractions));
      out.println("  Because of join nodes:           " + valueWithPercentage(blk.numBlkJoins.getValue(), numAbstractions));
      out.println("  Because of threshold:            " + valueWithPercentage(blk.numBlkThreshold.getValue(), numAbstractions));
      out.println("  Because of target state:         " + valueWithPercentage(statistics.numTargetAbstractions.getUpdateCount(), numAbstractions));
      out.println("  Times precision was empty:       " + valueWithPercentage(as.numSymbolicAbstractions, as.numCallsAbstraction));
      out.println("  Times precision was {false}:     " + valueWithPercentage(as.numSatCheckAbstractions, as.numCallsAbstraction));
      out.println("  Times result was cached:         " + valueWithPercentage(as.numCallsAbstractionCached, as.numCallsAbstraction));
      out.println("  Times cartesian abs was used:    " + valueWithPercentage(as.cartesianAbstractionTime.getNumberOfIntervals(), as.numCallsAbstraction));
      out.println("  Times boolean abs was used:      " + valueWithPercentage(as.booleanAbstractionTime.getNumberOfIntervals(), as.numCallsAbstraction));
      out.println("  Times result was 'false':        " + valueWithPercentage(statistics.numAbstractionsFalse.getUpdateCount(), numAbstractions));
      if (as.inductivePredicatesTime.getNumberOfIntervals() > 0) {
        out.println(
            "  Times inductive cache was used:  "
                + valueWithPercentage(as.numInductivePathFormulaCacheUsed, as.numCallsAbstraction));
      }
    }

    if (statistics.satCheckTimer.getNumberOfIntervals() > 0) {
      out.println("Number of satisfiability checks:   " + statistics.satCheckTimer.getNumberOfIntervals());
      out.println("  Times result was 'false':        " + statistics.numSatChecksFalse + " (" + toPercent(statistics.numSatChecksFalse.getUpdateCount(), statistics.satCheckTimer.getNumberOfIntervals()) + ")");
    }
    out.println("Number of strengthen sat checks:   " + statistics.strengthenCheckTimer.getNumberOfIntervals());
    if (statistics.strengthenCheckTimer.getNumberOfIntervals() > 0) {
      out.println("  Times result was 'false':        " + statistics.numStrengthenChecksFalse + " (" + toPercent(statistics.numStrengthenChecksFalse.getUpdateCount(), statistics.strengthenCheckTimer.getNumberOfIntervals()) + ")");
    }
    out.println("Number of coverage checks:         " + statistics.coverageCheckTimer.getNumberOfIntervals());
    out.println("  BDD entailment checks:           " + statistics.bddCoverageCheckTimer.getNumberOfIntervals());
    if (statistics.symbolicCoverageCheckTimer.getNumberOfIntervals() > 0) {
      out.println("  Symbolic coverage check:         " + statistics.symbolicCoverageCheckTimer.getNumberOfIntervals());
    }
    out.println("Number of SMT sat checks:          " + solver.satChecks);
    out.println("  trivial:                         " + solver.trivialSatChecks);
    out.println("  cached:                          " + solver.cachedSatChecks);
    out.println();
    out.println("Max ABE block size:                       " + statistics.blockSize.getMaxValue());
    put(out, 0, statistics.blockSize);
    out.println("Number of predicates discovered:          " + allDistinctPreds);
    if (precisionStatistics && allDistinctPreds > 0) {
      out.println("Number of abstraction locations:          " + allLocs);
      out.println("Max number of predicates per location:    " + maxPredsPerLocation);
      out.println("Avg number of predicates per location:    " + avgPredsPerLocation);
    }
    if (as.numCallsAbstraction - as.numSymbolicAbstractions > 0) {
      int numRealAbstractions = as.numCallsAbstraction - as.numSymbolicAbstractions - as.numCallsAbstractionCached;
      out.println("Total predicates per abstraction:         " + as.numTotalPredicates);
      out.println("Max number of predicates per abstraction: " + as.maxPredicates);
      out.println("Avg number of predicates per abstraction: " + div(as.numTotalPredicates, numRealAbstractions));
      out.println("Number of irrelevant predicates:          " + valueWithPercentage(as.numIrrelevantPredicates, as.numTotalPredicates));
      if (as.trivialPredicatesTime.getNumberOfIntervals() > 0) {
        out.println("Number of trivially used predicates:      " + valueWithPercentage(as.numTrivialPredicates, as.numTotalPredicates));
      }
      if (as.inductivePredicatesTime.getNumberOfIntervals() > 0) {
        out.println(
            "Number of inductive predicates:           "
                + valueWithPercentage(as.numInductivePredicates, as.numTotalPredicates));
      }
      if (as.cartesianAbstractionTime.getNumberOfIntervals() > 0) {
        out.println("Number of preds cached for cartesian abs: " + valueWithPercentage(as.numCartesianAbsPredicatesCached, as.numTotalPredicates));
        out.println("Number of preds solved by cartesian abs:  " + valueWithPercentage(as.numCartesianAbsPredicates, as.numTotalPredicates));
      }
      if (as.booleanAbstractionTime.getNumberOfIntervals() > 0) {
        out.println("Number of preds handled by boolean abs:   " + valueWithPercentage(as.numBooleanAbsPredicates, as.numTotalPredicates));
        out.println("  Total number of models for allsat:      " + as.allSatCount);
        out.println("  Max number of models for allsat:        " + as.maxAllSatCount);
        out.println("  Avg number of models for allsat:        " + div(as.allSatCount, as.booleanAbstractionTime.getNumberOfIntervals()));
      }
    }
    out.println();

    put(out, 0, statistics.postTimer);
    put(out, 1, statistics.pathFormulaTimer);
    if (statistics.satCheckTimer.getNumberOfIntervals() > 0) {
      put(out, 1, statistics.satCheckTimer);
    }
    put(out, 0, statistics.strengthenTimer);
    if (statistics.strengthenCheckTimer.getNumberOfIntervals() > 0) {
      put(out, 1, statistics.strengthenCheckTimer);
    }
    put(out, 0, statistics.totalPrecTime);
    if (numAbstractions > 0) {
      out.println("  Time for abstraction:              " + statistics.computingAbstractionTime + " (Max: " + statistics.computingAbstractionTime.getMaxTime().formatAs(SECONDS) + ", Count: " + statistics.computingAbstractionTime.getNumberOfIntervals() + ")");
      if (as.trivialPredicatesTime.getNumberOfIntervals() > 0) {
        out.println("    Relevant predicate analysis:     " + as.trivialPredicatesTime);
      }
      if (as.inductivePredicatesTime.getNumberOfIntervals() > 0) {
        out.println("    Inductive predicate analysis:    " + as.inductivePredicatesTime);
      }
      if (as.cartesianAbstractionTime.getNumberOfIntervals() > 0) {
        out.println("    Cartesian abstraction:           " + as.cartesianAbstractionTime);
      }
      if (as.booleanAbstractionTime.getNumberOfIntervals() > 0) {
        out.println("    Boolean abstraction:             " + as.booleanAbstractionTime);
      }
      if (as.abstractionReuseTime.getNumberOfIntervals() > 0) {
        out.println("    Abstraction reuse:              " + as.abstractionReuseTime);
        out.println("    Abstraction reuse implication:  " + as.abstractionReuseImplicationTime);
      }
      out.println("    Solving time:                    " + as.abstractionSolveTime + " (Max: " + as.abstractionSolveTime.getMaxTime().formatAs(SECONDS) + ")");
      out.println("    Model enumeration time:          " + as.abstractionEnumTime.getOuterSumTime().formatAs(SECONDS));
      out.println("    Time for BDD construction:       " + as.abstractionEnumTime.getInnerSumTime().formatAs(SECONDS)   + " (Max: " + as.abstractionEnumTime.getInnerMaxTime().formatAs(SECONDS) + ")");
    }

    if (statistics.totalMergeTime.getNumberOfIntervals() != 0) { // at least used once
      put(out, 0, statistics.totalMergeTime);
    }

    put(out, 0, statistics.coverageCheckTimer);
    if (statistics.bddCoverageCheckTimer.getNumberOfIntervals() > 0) {
      put(out, 1, statistics.bddCoverageCheckTimer);
    }
    if (statistics.symbolicCoverageCheckTimer.getNumberOfIntervals() > 0) {
      put(out, 1, statistics.symbolicCoverageCheckTimer);
    }
    out.println("Total time for SMT solver (w/o itp): " + TimeSpan.sum(solver.solverTime.getSumTime(), as.abstractionSolveTime.getSumTime(), as.abstractionEnumTime.getOuterSumTime()).formatAs(SECONDS));

    if (statistics.abstractionCheckTimer.getNumberOfIntervals() > 0) {
      put(out, 0, statistics.abstractionCheckTimer);
      out.println("Time for unsat checks:             " + statistics.satCheckTimer + " (Calls: " + statistics.satCheckTimer.getNumberOfIntervals() + ")");
    }
    out.println();
    pfmgr.printStatistics(out);
    out.println();
    rmgr.printStatistics(out);
  }
}
