// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2021 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.util.smg.graph;

import java.math.BigInteger;

public abstract class SMGRegion extends SMGObject {

  protected SMGRegion(int pNestingLevel, BigInteger pSize, BigInteger pOffset, boolean pValid) {
    super(pNestingLevel, pSize, pOffset, pValid);
  }
}