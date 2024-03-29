// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

extern int __VERIFIER_nondet_int();

int main(){
	int x = __VERIFIER_nondet_int();
	int y = __VERIFIER_nondet_int();
	if (y > 0)
		x = 1;
	else
		x = 0;
	if (x == 0)
		goto ERROR;
EXIT: return 0;
ERROR: return 1;
}
