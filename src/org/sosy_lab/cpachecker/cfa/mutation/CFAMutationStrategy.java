// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import org.sosy_lab.cpachecker.cfa.ParseResult;

class CFAMutationStrategy {

  public boolean canMutate(ParseResult pCfa) {
    // TODO Auto-generated method stub

    // TODO strategy can analyze CFA, and gets info
    // e.g. function remover gets which function is main -- so it does not remove it
    return false;
  }

  public ParseResult mutate(ParseResult pCfa) {
    // TODO Auto-generated method stub
    return null;
  }

  public ParseResult rollback(ParseResult pCfa) {
    // TODO Auto-generated method stub
    return null;
  }
}
