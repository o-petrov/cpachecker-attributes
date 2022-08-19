// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.core.CPAcheckerResult.Result;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;
import org.sosy_lab.cpachecker.core.reachedset.UnmodifiableReachedSet;
import org.sosy_lab.cpachecker.util.statistics.StatCounter;
import org.sosy_lab.cpachecker.util.statistics.StatInt;
import org.sosy_lab.cpachecker.util.statistics.StatKind;
import org.sosy_lab.cpachecker.util.statistics.StatTimer;
import org.sosy_lab.cpachecker.util.statistics.StatTimerWithMoreOutput;

enum DeltaDebuggingStage {
  INIT,
  REMOVE_COMPLEMENT,
  REMOVE_DELTA,
  REMOVE_SAFE,
  DONE
}

/**
 * Generic strategy that chooses how to mutate a CFA using Delta Debugging approach.
 *
 * <p>See Zeller, A. (1999). Yesterday, my Program Worked. Today, it Does Not. Why?. In: Nierstrasz,
 * O., Lemoine, M. (eds) Software Engineering — ESEC/FSE ’99. ESEC SIGSOFT FSE 1999 1999. Lecture
 * Notes in Computer Science, vol 1687. Springer, Berlin, Heidelberg. <a
 * href="https://doi.org/10.1007/3-540-48166-4_16">https://doi.org/10.1007/3-540-48166-4_16</a>
 *
 * <p>or <a href="https://www.youtube.com/watch?v=SzRqd4YeLlM">Learning from Code History</a>, a
 * video presentation at Google Tech Talk
 *
 * <p>The strategy implements so called ddmin algorithm, that finds a minimal cause of a test fail.
 * Here a test is input program in form of CFA, that consists of some generic objects. These objects
 * should be independent, so they can be removed in any combinations or order. The ddmin algorithm
 * divides this set of objects into so called deltas. A complement is all objects remaining in the
 * CFA except for the corresponding delta.
 *
 * <p>Every time a part is removed from program (i.e. CFA is mutated), the analysis is run.
 *
 * <p>If it results in same error as analysis on full program (In terms of DD, result of a test run
 * is {@link DDResultOfARun#FAIL}), then the cause of the error remains in program, and removed part
 * is not returned (mutation is not rollbacked). ddmin continues to minimize cause on remaining
 * objects.
 *
 * <p>If it results in TRUE or FALSE verdict ({@link DDResultOfARun#PASS} in terms of DD), the cause
 * is inside removed objects, and remained objects are marked as safe. Mutation is rollbacked and
 * ddmin continues to minimize cause on returned objects.
 *
 * <p>If it results in some problem other than sought-for error, e.g. TIMEOUT, UNKNOWN, or other
 * exception ({@link DDResultOfARun#UNRESOLVED} in terms of DD), mutation is rollbacked. ddmin can
 * not reduce potential cause set.
 *
 * <p>The steps of the implemented algorithm are as follows:
 *
 * <p>1. All objects are considered as one delta and are removed. If analysis run results in FAIL, a
 * minimal cause is somewhere else, and the algorithm stops.
 *
 * <p>2. Otherwise, the delta is divided in two halves. One half is removed. If analysis run results
 * in FAIL, the other half contains a minimal cause. If result is PASS, the remaining half is safe
 * by itself[1], so a minimal cause is inside removed half. If result is UNRESOLVED, the other half
 * is checked the same way.
 *
 * <p>3. If one half remains, or one half is decided to contain a minimal cause, that half is
 * divided in halves (new deltas) and steps 2–3 are repeated.
 *
 * <p>4. Otherwise both halves contain some part of a minimal cause, so divide each in two halves.
 * First, all complements are checked one by one.
 *
 * <p>5. If a complement is removed, and analysis results in a FAIL, a minimal cause is in single
 * remaining delta. this delta is halved, goto step 2. If analysis result in a PASS, the remaining
 * delta is marked as safe. It is not removed from CFA, but it is not considered by ddmin algorithm
 * anymore. If analysis results in PASS or UNRESOLVED, the complement is returned to the CFA.
 *
 * <p>6. Now every complement is checked, and none of them is removed. If only one of remaining
 * deltas is not marked as safe, it is halved, goto step 2. Otherwise, try to remove these deltas
 * one by one.
 *
 * <p>7. If FAIL, delta is marked as safe. (It seems the complement has both cause and something
 * neccessary for a correct analysis run.) If PASS, the delta has a minimal cause. (It seems the
 * complement has something neccessary to resolve a run.) If PASS or UNRESOLVED, the delta is
 * returned.
 *
 * <p>8. Now every delta is checked. If only one of remaining deltas is not marked as safe, it is
 * halved, goto step 2. Otherwise all deltas that are not marked as safe are divided in two parts,
 * goto step 5.
 *
 * <p>If a delta can not be divided in two halves, i.e. it consists of one object, it is definitely
 * part of the found minimal cause.
 *
 * <p>ddmin ends when every object is either removed, or marked as safe (is in a safe part), or
 * decided to be part of the found minimal cause.
 *
 * <p>As the goal of the CFAMutator is to minimize not the cause, but the whole CFA, it then tries
 * to remove objects that are marked as safe.
 *
 * <p>[1] It is possible, that some objects are actually part of the cause, i.e. without them the
 * sought-for bug does not occur. E.g. the bug needs all of the cause objects to be present, so if
 * any are removed, the analysis runs correctly, and all remaining objects are marked as safe. In
 * such case only one of the cause objects is decided to be a minimal cause.
 *
 * <p>As the PASS result means the remaining part is safe by itself, and we can not use this
 * information on this stage, the PASS is actually the same as UNRESOLVED, so there is no more
 * benefit of removing a complement to get a PASS. Because of this, the implemented algorithm tries
 * to remove 'safe' objects as deltas only.
 */
// TODO better documentation, choose terms and use them consistently througout the class
// (remove delta from CFA vs. from unresolved objects)
abstract class GenericDeltaDebuggingStrategy<RemoveObject, RestoreObject>
    implements CFAMutationStrategy {

  protected class GenericDeltaDebuggingStatistics implements Statistics {
    private final String name;

    private final StatCounter passes = new StatCounter("passes");
    private final StatCounter totalRounds = new StatCounter("mutation rounds");
    private final StatCounter passRounds = new StatCounter("unsuccessful, no errors");
    private final StatCounter unresRounds = new StatCounter("unsuccessful, other problems");

    private final StatTimer totalTimer = new StatTimerWithMoreOutput("total time for strategy");

    private final StatInt unresolvedCount =
        new StatInt(StatKind.SUM, "count of unresolved " + objectsTitle);
    private final StatInt safeCount = new StatInt(StatKind.SUM, "count of safe " + objectsTitle);
    private final StatInt removedCount =
        new StatInt(StatKind.SUM, "count of removed " + objectsTitle);
    private final StatInt totalCount = new StatInt(StatKind.SUM, "count of found " + objectsTitle);

    protected GenericDeltaDebuggingStatistics(String pName) {
      name = pName;
    }

    @Override
    public void printStatistics(PrintStream pOut, Result pResult, UnmodifiableReachedSet pReached) {
      if (passes.getValue() > 0) {
        put(pOut, 1, passes);
      }
      put(pOut, 1, totalRounds);
      put(pOut, 2, passRounds);
      put(pOut, 2, unresRounds);
      put(pOut, 1, totalTimer);
      put(pOut, 1, totalCount);
      put(pOut, 2, "count of cause " + objectsTitle, causeObjects.size());
      put(pOut, 2, safeCount);
      put(pOut, 2, removedCount);
      put(pOut, 2, unresolvedCount);
    }

    @Override
    public @Nullable String getName() {
      return name;
    }
  }

  /**
   * Return all possible mutations of this kind in a list, that will be mutated by the strategy.
   * Mutations need to be applicable in arbitrary combinations.
   */
  protected abstract List<RemoveObject> getAllObjects(FunctionCFAsWithMetadata pCfa);
  /** How to apply chosen mutation */
  protected abstract RestoreObject removeObject(
      FunctionCFAsWithMetadata pCfa, RemoveObject pChosen);
  /** How to rollback a mutation */
  protected abstract void restoreObject(FunctionCFAsWithMetadata pCfa, RestoreObject pRemoved);

  /** All objects to investigate in next {@link #mutate} calls */
  private List<RemoveObject> unresolvedObjects = null;
  /** All objects that remain in CFA, but appear to be safe */
  private List<RemoveObject> safeObjects = new ArrayList<>();
  /** All objects that appear to be the cause, i.e. result of DD algorithm */
  private List<RemoveObject> causeObjects = new ArrayList<>();
  /** Objects that are removed in current round */
  private ImmutableList<RemoveObject> currentMutation = null;
  /** Store info to rollback current mutation if needed */
  private ImmutableList<RestoreObject> rollbackInfos = null;

  /** Save stage of DD algorithm between calls to {@link #mutate} */
  private DeltaDebuggingStage stage = DeltaDebuggingStage.INIT;

  private List<ImmutableList<RemoveObject>> deltaList = null;
  private Iterator<ImmutableList<RemoveObject>> deltaIter = null;
  private ImmutableList<RemoveObject> currentDelta = null;

  protected final LogManager logger;
  private final String objectsTitle;
  protected GenericDeltaDebuggingStatistics stats;

  public GenericDeltaDebuggingStrategy(LogManager pLogger, String pObjectsTitle) {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(pObjectsTitle));
    logger = Preconditions.checkNotNull(pLogger);
    objectsTitle = pObjectsTitle;
    stats = new GenericDeltaDebuggingStatistics(this.getClass().getSimpleName());
  }

  @Override
  public void collectStatistics(Collection<Statistics> pStatsCollection) {
    pStatsCollection.add(stats);
  }

  protected void reset() {
    stats.passes.inc();
    stage = DeltaDebuggingStage.INIT;
  }

  @Override
  public boolean canMutate(FunctionCFAsWithMetadata pCfa) {
    stats.totalTimer.start();
    boolean result;

    // corner cases
    switch (stage) {
      case INIT:
        // set up if it is first call
        unresolvedObjects = getAllObjects(pCfa);
        stats.unresolvedCount.setNextValue(unresolvedObjects.size());
        stats.totalCount.setNextValue(unresolvedObjects.size());
        if (unresolvedObjects.isEmpty()) {
          // nothing to do
          logger.log(Level.INFO, "No", objectsTitle, "to mutate");
          result = false;
          break;
        }
        logger.log(Level.INFO, "Got", unresolvedObjects.size(), objectsTitle, "to remove");
        resetDeltaListWithOneDelta(ImmutableList.copyOf(unresolvedObjects));
        stage = DeltaDebuggingStage.REMOVE_DELTA;
        result = true;
        break;

      case REMOVE_DELTA:
        if (!deltaIter.hasNext()) {
          // tried all deltas, partition again
          logger.log(
              Level.INFO,
              "halving remained",
              deltaList.size(),
              "deltas with total",
              unresolvedObjects.size(),
              objectsTitle);
          stage = DeltaDebuggingStage.REMOVE_COMPLEMENT;
          halveDeltas();
        }

        // DD can end only in delta-removing stage, when deltaList is empty
        if (unresolvedObjects.isEmpty()) {
          logger.log(
              Level.INFO,
              "All",
              objectsTitle,
              "are resolved,",
              causeObjects.size(),
              "cause",
              objectsTitle,
              "and",
              safeObjects.size(),
              "safe",
              objectsTitle,
              "remain");
          logger.log(Level.INFO, "Cause", objectsTitle, "are:", causeObjects);
          logger.log(Level.INFO, "Safe", objectsTitle, "are:", safeObjects);
          if (safeObjects.isEmpty()) {
            stage = DeltaDebuggingStage.DONE;
            result = false;
            break;
          }
          stage = DeltaDebuggingStage.REMOVE_SAFE;
          resetDeltaListWithOneDelta(ImmutableList.copyOf(safeObjects));
        }

        result = true;
        break;

      case REMOVE_COMPLEMENT:
        assert !unresolvedObjects.isEmpty();
        if (!deltaIter.hasNext()) {
          // tried all complements, switch to deltas
          stage = DeltaDebuggingStage.REMOVE_DELTA;
          deltaIter = deltaList.iterator();
          currentDelta = null;
        }
        result = true;
        break;

      case REMOVE_SAFE:
        assert unresolvedObjects.isEmpty();
        if (safeObjects.isEmpty()) {
          logger.log(Level.INFO, "No safe", objectsTitle, "to remove");
          stage = DeltaDebuggingStage.DONE;
          result = false;
          break;
        }
        result = true;
        break;

      case DONE: // everything was already done
        result = false;
        break;

      default:
        throw new AssertionError();
    }

    stats.totalTimer.stop();
    return result;
  }

  /**
   * Choose some objects from the CFA using Delta Debugging approach and remove them from the CFA.
   *
   * <p>Although this class is described in terms of removing an object, this can actually mean any
   * mutation of some part in CFA, e.g. replacing an edge with another one. Then restoring such an
   * object is replacing new edge back with the old one.
   */
  @Override
  public void mutate(FunctionCFAsWithMetadata pCfa) {
    stats.totalTimer.start();
    stats.totalRounds.inc();

    // set next mutation
    // not in the switch of #canMutate because stage can be changed there
    switch (stage) {
      case REMOVE_COMPLEMENT:
        currentDelta = deltaIter.next();
        currentMutation =
            ImmutableList.copyOf(
                unresolvedObjects.stream().filter(o -> !currentDelta.contains(o)).iterator());
        logger.log(Level.INFO, "removing a complement of", currentMutation.size(), objectsTitle);
        break;


      case REMOVE_DELTA:
        currentDelta = deltaIter.next();
        currentMutation = currentDelta;
        logger.log(Level.INFO, "removing a delta of", currentMutation.size(), objectsTitle);
        break;

      case REMOVE_SAFE:
        currentDelta = deltaIter.next();
        currentMutation = currentDelta;
        logger.log(Level.INFO, "removing a delta of", currentMutation.size(), "safe", objectsTitle);
        break;

      default:
        throw new AssertionError();
    }
    // remove objects (apply mutations)
    rollbackInfos =
        ImmutableList.copyOf(
            currentMutation.stream().map(mutation -> removeObject(pCfa, mutation)).iterator());

    stats.totalTimer.stop();
  }

  @Override
  public void setResult(FunctionCFAsWithMetadata pCfa, DDResultOfARun pResult) {
    stats.totalTimer.start();

    if (stage == DeltaDebuggingStage.REMOVE_SAFE) {
      if (pResult == DDResultOfARun.FAIL) {
        safeObjects.removeAll(currentMutation);
        stats.safeCount.setNextValue(-currentMutation.size());
        stats.removedCount.setNextValue(currentMutation.size());
        logger.log(
            Level.INFO, "Successfully removed", currentMutation.size(), "safe", objectsTitle);
        deltaIter.remove();
      } else {
        logger.log(
            Level.WARNING,
            "Can not remove all safe",
            objectsTitle,
            "at once. Removing them as deltas");
        // restore objects
        rollbackInfos.reverse().forEach(r -> restoreObject(pCfa, r));
      }

      if (deltaList.size() == 1) {
        logger.log(
            Level.INFO, "halving single delta of", deltaList.get(0).size(), "safe", objectsTitle);
        halveDeltas();
      } else if (!deltaIter.hasNext()) {
        // tried all deltas, partition again
        logger.log(
            Level.INFO,
            "halving remained",
            deltaList.size(),
            "deltas with total",
            safeObjects.size(),
            "safe",
            objectsTitle);
        halveDeltas();
      }

      stats.totalTimer.stop();
      return;
    }

    // update safe and unresolved sets if possible
    switch (pResult) {
      case FAIL:
        // Error is present, do not restore removed objects.
        // Removed objects were safe, but are already removed.
        logger.log(
            Level.INFO, "Removed", objectsTitle, "are safe. Cause is inside remain", objectsTitle);
        unresolvedObjects.removeAll(currentMutation);
        stats.unresolvedCount.setNextValue(-currentMutation.size());
        stats.removedCount.setNextValue(currentMutation.size());
        break;

      case PASS:
        stats.passRounds.inc();
        // No problems occur, last mutations hid the error.
        // Unresolved objects minus applied mutations are actually safe
        // No need to check them, but they remain in CFA.
        logger.log(
            Level.INFO,
            "Cause is inside removed",
            objectsTitle + ". Remained",
            objectsTitle,
            "are safe");
        safeObjects.addAll(unresolvedObjects);
        safeObjects.removeAll(currentMutation);
        stats.safeCount.setNextValue(unresolvedObjects.size() - currentMutation.size());
        stats.unresolvedCount.setNextValue(currentMutation.size() - unresolvedObjects.size());
        unresolvedObjects.retainAll(currentMutation);

        // restore objects
        rollbackInfos.reverse().forEach(r -> restoreObject(pCfa, r));
        break;

      case UNRESOLVED:
        stats.unresRounds.inc();
        // some other problem, just undo mutation
        logger.log(
            Level.INFO,
            "Some of removed",
            objectsTitle,
            "are needed for correct run. No",
            objectsTitle,
            "resolved");
        rollbackInfos.reverse().forEach(r -> restoreObject(pCfa, r));
        break;

      default:
        throw new AssertionError();
    }

    // TODO REMOVE_WHOLE AS DELTA
    // update delta list too
    if ((stage == DeltaDebuggingStage.REMOVE_DELTA && pResult == DDResultOfARun.FAIL)
        || (stage == DeltaDebuggingStage.REMOVE_COMPLEMENT && pResult == DDResultOfARun.PASS)) {
      logger.log(Level.INFO, "This delta is safe");
      // delta is safe, complement has the cause
      // Remove delta from list
      deltaIter.remove();

    } else if ((stage == DeltaDebuggingStage.REMOVE_DELTA && pResult == DDResultOfARun.PASS)
        || (stage == DeltaDebuggingStage.REMOVE_COMPLEMENT && pResult == DDResultOfARun.FAIL)) {
      logger.log(Level.INFO, "This complement is safe");
      // delta has the cause, complement is safe
      // Remove complement from list, i.e. make list of one delta, and then split it.
      resetDeltaListWithOneDelta(currentDelta);
      logger.log(Level.INFO, "halving single delta of", deltaList.get(0).size(), objectsTitle);
      halveDeltas();
      // switch from REMOVE_COMPLEMENT
      stage = DeltaDebuggingStage.REMOVE_DELTA;

    } else {
      // Can neither assume safe/cause nor update delta list if pResult == UNRESOLVED
      assert stage != DeltaDebuggingStage.INIT;
      assert pResult == DDResultOfARun.UNRESOLVED;
    }

    currentMutation = null;
    rollbackInfos = null;
    stats.totalTimer.stop();
  }

  /**
   * Return the CFA parts that cause exception in analysis fail. Do not call this method until
   * {@link #canMutate} returns false, as the cause may be not identified.
   *
   * @return all the objects that remain in CFA and are not safe.
   */
  public ImmutableList<RemoveObject> getCauseObjects() {
    return ImmutableList.copyOf(causeObjects);
  }

  /**
   * Return the safe part that remains in the CFA. Do not call this method until {@link #canMutate}
   * returns false, as the cause is not identified.
   *
   * @return all the objects that remain in CFA and seem to be safe.
   */
  public ImmutableList<RemoveObject> getRemainedSafeObjects() {
    return ImmutableList.copyOf(safeObjects);
  }

  // XXX Some objects are exclusive to others, e.g.
  // 1. We can't exactly remove both branches of one branching node.
  // 2. See SingleNodeRemover
  // So, number of objects is likely to be not consistent during run.
  // XXX mark exclusive alternatives? or just abstract count?
  private void halveDeltas() {
    var result = new ArrayList<ImmutableList<RemoveObject>>(deltaList.size() * 2);

    for (var delta : deltaList) {
      assert !delta.isEmpty();
      if (delta.size() == 1) {
        // This delta is one object, that is not cause by itself
        // and can not be safe by itself. So, it is part of the cause.
        causeObjects.add(delta.get(0));
        if (stage == DeltaDebuggingStage.REMOVE_SAFE) {
          safeObjects.remove(delta.get(0));
          stats.safeCount.setNextValue(-1);
        } else {
          unresolvedObjects.remove(delta.get(0));
          stats.unresolvedCount.setNextValue(-1);
        }

      } else {
        int half = (1 + delta.size()) / 2;
        result.add(delta.subList(0, half));
        result.add(delta.subList(half, delta.size()));
      }
    }

    resetDeltaList(result);
  }

  private void resetDeltaList(List<ImmutableList<RemoveObject>> pDeltaList) {
    deltaList = pDeltaList;
    deltaIter = deltaList.iterator();
    currentDelta = null;
  }

  private void resetDeltaListWithOneDelta(ImmutableList<RemoveObject> pOnlyDelta) {
    List<ImmutableList<RemoveObject>> list = new ArrayList<>(1);
    list.add(pOnlyDelta);
    resetDeltaList(list);
  }
}
