// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.types.c;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.Serializable;
import java.util.Objects;
import java.util.OptionalInt;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class CPointerType implements CType, Serializable {

  private static final long serialVersionUID = -6423006826454509009L;
  public static final CPointerType POINTER_TO_VOID = new CPointerType(false, false, CVoidType.VOID);
  public static final CPointerType POINTER_TO_CHAR =
      new CPointerType(false, false, CNumericTypes.CHAR);
  public static final CPointerType POINTER_TO_CONST_CHAR = new CPointerType(false, false, CNumericTypes.CHAR.getCanonicalType(true, false));

  private final CType type;
  private final boolean isConst;
  private final boolean isVolatile;
  private final OptionalInt alignment;
  private final boolean isMember;

  public CPointerType(
      boolean pConst, boolean pVolatile, OptionalInt pAlignment, boolean pMember, CType pType) {
    isConst = pConst;
    isVolatile = pVolatile;
    alignment = pAlignment;
    isMember = pMember;
    type = checkNotNull(pType);
  }

  public CPointerType(boolean pConst, boolean pVolatile, CType pType) {
    this(pConst, pVolatile, OptionalInt.empty(), false, pType);
  }

  @Override
  public boolean isConst() {
    return isConst;
  }

  @Override
  public boolean isVolatile() {
    return isVolatile;
  }

  @Override
  public OptionalInt getAlignment() {
    return alignment;
  }

  @Override
  public boolean isMember() {
    return isMember;
  }

  public CType getType() {
    return type;
  }

  @Override
  public boolean isIncomplete() {
    return false;
  }

  @Override
  public String toString() {
    String decl = "(" + type + ")*";
    String align =
        alignment.isPresent() ? " __attribute__((__aligned__(" + alignment.getAsInt() + ")))" : "";

    return (isConst() ? "const " : "")
        + (isVolatile() ? "volatile " : "")
        + decl
        + align;
  }

  @Override
  public String toASTString(String pDeclarator) {
    checkNotNull(pDeclarator);
    // ugly hack but it works:
    // We need to insert the "*" and qualifiers between the type and the name (e.g. "int *var").
    StringBuilder inner = new StringBuilder("*");
    if (isConst()) {
      inner.append(" const");
    }
    if (isVolatile()) {
      inner.append(" volatile");
    }
    if (alignment.isPresent()) {
      inner.append(" __attribute__((__aligned__(" + alignment.getAsInt() + ")))");
    }

    if (inner.length() > 1) {
      inner.append(' ');
    }
    inner.append(pDeclarator);

    if (type instanceof CArrayType) {
      return type.toASTString("(" + inner + ")");
    } else {
      return type.toASTString(inner.toString());
    }
  }

  @Override
  public <R, X extends Exception> R accept(CTypeVisitor<R, X> pVisitor) throws X {
    return pVisitor.visit(this);
  }

  @Override
  public int hashCode() {
    return Objects.hash(isConst, isVolatile, isMember, alignment, type);
  }

  /**
   * Be careful, this method compares the CType as it is to the given object,
   * typedefs won't be resolved. If you want to compare the type without having
   * typedefs in it use #getCanonicalType().equals()
   */
  @Override
  public boolean equals(@Nullable Object obj) {
    if (obj == this) {
      return true;
    }

    if (!(obj instanceof CPointerType)) {
      return false;
    }

    CPointerType other = (CPointerType) obj;

    return isConst == other.isConst
        && isVolatile == other.isVolatile
        && isMember == other.isMember
        && alignment.equals(other.alignment)
        && Objects.equals(type, other.type);
  }

  @Override
  public CPointerType getCanonicalType() {
    return getCanonicalType(false, false);
  }

  @Override
  public CPointerType getCanonicalType(boolean pForceConst, boolean pForceVolatile) {
    return new CPointerType(
        isConst || pForceConst,
        isVolatile || pForceVolatile,
        alignment,
        isMember,
        type.getCanonicalType());
  }
}
