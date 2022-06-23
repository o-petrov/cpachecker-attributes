// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

enum {
  c = ((signed char) (((unsigned char) ~0ull) >> 1)) // max of signed char
} x;

int main () {
  x = -1ll;
  if ((x >> 1) > 0 /* smax > 0 */)
    goto ERROR;

  return 0;
ERROR:
  return 1;
}
