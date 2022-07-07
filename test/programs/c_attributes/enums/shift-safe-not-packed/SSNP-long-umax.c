// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

enum {
  c = ((unsigned long) ~0ull) // max of unsigned long
} x;

int main () {
  x = -1ll;
  x = x >> 1;
  if (x != ((signed long) (((unsigned long) ~0ull) >> 1)) /* x != signed TYPE max */)
    goto ERROR;
  if (sizeof x != sizeof(long))
    goto ERROR;

  return 0;
ERROR:
  return 1;
}
