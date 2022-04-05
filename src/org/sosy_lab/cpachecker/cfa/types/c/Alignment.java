// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.types.c;

import com.google.errorprone.annotations.Immutable;
import java.io.Serializable;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * This class stores alignment attribute specified for c type, alignment attribute specified for
 * variable of c type, and _Alignas specifier for variable.
 *
 * <p>Documentation: https://gcc.gnu.org/onlinedocs/gcc/Common-Type-Attributes.html
 * https://gcc.gnu.org/onlinedocs/gcc/Common-Variable-Attributes.html
 * https://en.cppreference.com/w/c/language/_Alignas
 */
@Immutable
public class Alignment implements Serializable {
  private static final long serialVersionUID = -8482191760254848303L;

  private final int typeAligned;
  private final int varAligned;
  private final int alignas;

  public Alignment(int pTypeAligned, int pVarAligned, int pAlignas) {
    typeAligned = pTypeAligned;
    varAligned = pVarAligned;
    alignas = pAlignas;
  }

  // Zero is chosen because _Alignas(0) has no effect.
  public static final int NO_SPECIFIER = 0;
  public static final Alignment NO_SPECIFIERS =
      new Alignment(NO_SPECIFIER, NO_SPECIFIER, NO_SPECIFIER);

  public static Alignment ofType(int pTypeAligned) {
    return new Alignment(pTypeAligned, NO_SPECIFIER, NO_SPECIFIER);
  }

  public static Alignment ofVar(int pVarAligned) {
    return new Alignment(NO_SPECIFIER, pVarAligned, NO_SPECIFIER);
  }

  public static Alignment ofAlignas(int pAlignas) {
    return new Alignment(NO_SPECIFIER, NO_SPECIFIER, pAlignas);
  }

  public Alignment withTypeAligned(int pTypeAligned) {
    return new Alignment(pTypeAligned, varAligned, alignas);
  }

  public Alignment withVarAligned(int pVarAligned) {
    return new Alignment(typeAligned, pVarAligned, alignas);
  }

  public Alignment withAlignas(int pAlignas) {
    return new Alignment(typeAligned, varAligned, pAlignas);
  }

  public int getTypeAligned() {
    return typeAligned;
  }

  public int getVarAligned() {
    return varAligned;
  }

  public int getAlignas() {
    return alignas;
  }

  public String stringVarAligned() {
    return typeAligned == NO_SPECIFIER ? "" : " __attribute__((__aligned__(" + typeAligned + "))) ";
  }

  public String stringTypeAligned() {
    return varAligned == NO_SPECIFIER ? "" : " __attribute__((__aligned__(" + varAligned + "))) ";
  }

  public String stringAlignas() {
    return alignas == NO_SPECIFIER ? "" : "_Alignas(" + alignas + ") ";
  }

  @Override
  public int hashCode() {
    return Objects.hash(typeAligned, varAligned, alignas);
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (obj == this) {
      return true;
    }

    if (!(obj instanceof Alignment)) {
      return false;
    }

    Alignment other = (Alignment) obj;

    return typeAligned == other.typeAligned
        && varAligned == other.varAligned
        && alignas == other.alignas;
  }
}