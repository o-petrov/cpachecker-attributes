// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.types.c;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class CPointerType implements CType, Serializable {

  private static final long serialVersionUID = -6423006826454509009L;
  public static final CPointerType POINTER_TO_VOID =
      new CPointerType(false, false, Alignment.NO_SPECIFIERS, CVoidType.VOID);
  public static final CPointerType POINTER_TO_CHAR =
      new CPointerType(false, false, Alignment.NO_SPECIFIERS, CNumericTypes.CHAR);
  public static final CPointerType POINTER_TO_CONST_CHAR =
      new CPointerType(
          false, false, Alignment.NO_SPECIFIERS, CNumericTypes.CHAR.getCanonicalType(true, false));

  private final CType type;
  private final boolean isConst;
  private final boolean isVolatile;

  /**
   * As usual, <code>_Alignas</code> specifier and <code>aligned</code> attribute align pointer
   * variable. But if aligned attribute is specified after <code>*</code>, it aligns 'type' itself,
   * i.e. <code>int * __aligned(1) p;</code> means that not only <code>p</code> is aligned, but also
   * {@code &p[zero]} and {@code (&p)[zero]}.
   *
   * <p>In GCC {@code &*...&*p} and {@code *&...*&p} are aligned as <code>p</code> anyway, but in
   * Clang they are not. <code>p[0]</code> always behaves as <code>*p</code>.
   */
  private final Alignment alignment;

  public CPointerType(boolean pConst, boolean pVolatile, CType pType) {
    this(pConst, pVolatile, Alignment.NO_SPECIFIERS, pType);
  }

  public CPointerType(boolean pConst, boolean pVolatile, Alignment pAlignment, CType pType) {
    isConst = pConst;
    isVolatile = pVolatile;
    alignment = checkNotNull(pAlignment);
    type = checkNotNull(pType);
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
  public Alignment getAlignment() {
    return alignment;
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
    return toASTString("");
  }

  @Override
  public String toASTString(String pDeclarator) {
    checkNotNull(pDeclarator);
    ArrayList<String> parts = new ArrayList<>();

    // ugly hack but it works:
    // We need to insert the "*" and qualifiers between the type and the name (e.g. "int *var").
    if (isConst()) {
      parts.add("const");
    }
    if (isVolatile()) {
      parts.add("volatile");
    }
    if (alignment.getTypeAligned() != Alignment.NO_SPECIFIER) {
      parts.add(alignment.stringTypeAligned());
    }
    // if any qualifiers, add a space after asterisk
    String asterisk = parts.size() == 0 ? "*" : "* ";

    parts.add(Strings.emptyToNull(pDeclarator));
    String declarator = asterisk + Joiner.on(' ').skipNulls().join(parts);
    parts.clear();
    parts.add(Strings.emptyToNull(alignment.stringAlignas()));
    // do not want to have different methods doing similar thing,
    // so explicitly treat composite type as elaborated type in toString
    if (type instanceof CCompositeType) {
      parts.add(((CCompositeType) type).toASTString(declarator, false));
    } else {
      parts.add(type.toASTString(declarator));
    }
    parts.add(Strings.emptyToNull(alignment.stringVarAligned()));

    return Joiner.on(' ').skipNulls().join(parts);
  }

  @Override
  public <R, X extends Exception> R accept(CTypeVisitor<R, X> pVisitor) throws X {
    return pVisitor.visit(this);
  }

  @Override
  public int hashCode() {
    return Objects.hash(isConst, isVolatile, alignment, type);
  }

  /**
   * Be careful, this method compares the CType as it is to the given object, typedefs won't be
   * resolved. If you want to compare the type without having typedefs in it use
   * #getCanonicalType().equals()
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
        isConst || pForceConst, isVolatile || pForceVolatile, alignment, type.getCanonicalType());
  }
}
