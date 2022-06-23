// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

enum {
  c = ((signed long long) (((signed long long) (((unsigned long long) ~0ull) >> 1)) + 1)) // min of signed long long
} x;

int main () {
  x = -1ll;
  x = x >> 1;
  if (x != -1 /* -1 != -1 as -1 >> 1 == -1 */)
    goto ERROR;
  if (sizeof x != sizeof(long long))
    goto ERROR;

  return 0;
ERROR:
  return 1;
}
