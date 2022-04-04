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
import java.util.OptionalInt;
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

  private static final int notSpecified = -1;

  private final int typeAligned;
  private final int varAligned;
  private final int alignas;

  public Alignment(
      @Nullable Integer pTypeAligned, @Nullable Integer pVarAligned, @Nullable Integer pAlignas) {
    typeAligned = pTypeAligned == null ? notSpecified : pTypeAligned;
    varAligned = pVarAligned == null ? notSpecified : pVarAligned;
    alignas = pAlignas == null ? notSpecified : pAlignas;
  }

  public static final Alignment NO_SPECIFIERS = new Alignment(null, null, null);

  public static Alignment ofType(int pTypeAligned) {
    return new Alignment(pTypeAligned, null, null);
  }

  public static Alignment ofVar(int pVarAligned) {
    return new Alignment(null, pVarAligned, null);
  }

  public static Alignment ofAlignas(int pAlignas) {
    return new Alignment(null, null, pAlignas);
  }

  public Alignment withTypeAligned(@Nullable Integer pTypeAligned) {
    return new Alignment(pTypeAligned, varAligned, alignas);
  }

  public Alignment withVarAligned(@Nullable Integer pVarAligned) {
    return new Alignment(typeAligned, pVarAligned, alignas);
  }

  public Alignment withAlignas(@Nullable Integer pAlignas) {
    return new Alignment(typeAligned, varAligned, pAlignas);
  }

  public OptionalInt getTypeAligned() {
    return typeAligned == notSpecified ? OptionalInt.empty() : OptionalInt.of(typeAligned);
  }

  public OptionalInt getVarAligned() {
    return varAligned == notSpecified ? OptionalInt.empty() : OptionalInt.of(varAligned);
  }

  public OptionalInt getAlignas() {
    return alignas == notSpecified ? OptionalInt.empty() : OptionalInt.of(alignas);
  }

  public String stringVarAligned() {
    return typeAligned == notSpecified ? "" : " __attribute__((__aligned__(" + typeAligned + "))) ";
  }

  public String stringTypeAligned() {
    return varAligned == notSpecified ? "" : " __attribute__((__aligned__(" + varAligned + "))) ";
  }

  public String stringAlignas() {
    return alignas == notSpecified ? "" : "_Alignas(" + alignas + ") ";
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