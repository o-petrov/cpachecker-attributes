// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.types.c;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class CElaboratedType implements CComplexType {

  private static final long serialVersionUID = -3566628634889842927L;
  private final ComplexTypeKind kind;
  private String name;
  private final String origName;
  private final boolean isConst;
  private final boolean isVolatile;
  private final @Nullable Integer alignment;
  private final Membership member;

  private int hashCache = 0;

  private @Nullable CComplexType realType = null;

  public CElaboratedType(
      boolean pConst,
      boolean pVolatile,
      @Nullable Integer pAlignment,
      Membership pMember,
      ComplexTypeKind pKind,
      String pName,
      String pOrigName,
      @Nullable CComplexType pRealType) {
    checkArgument(pRealType != null || pAlignment == null);
    isConst = pConst;
    isVolatile = pVolatile;
    alignment = pAlignment;
    member = checkNotNull(pMember);
    kind = checkNotNull(pKind);
    name = pName.intern();
    origName = pOrigName.intern();
    realType = pRealType;
  }

  public CElaboratedType(
      final boolean pConst,
      final boolean pVolatile,
      final ComplexTypeKind pKind,
      final String pName,
      final String pOrigName,
      final @Nullable CComplexType pRealType) {
    this(pConst, pVolatile, null, Membership.NOTAMEMBER, pKind, pName, pOrigName, pRealType);
  }

  @Override
  public String getName() {
    if (realType != null) {
      return realType.getName();
    }
    return name;
  }

  @Override
  public String getQualifiedName() {
    return (kind.toASTString() + " " + name).trim();
  }

  @Override
  public String getOrigName() {
    if (realType != null) {
      return realType.getOrigName();
    }
    return origName;
  }

  @Override
  public ComplexTypeKind getKind() {
    return kind;
  }

  /**
   * Get the real type which this type references
   * (either a CCompositeType or a CEnumType, or null if unknown).
   */
  public @Nullable CComplexType getRealType() {
    if (realType instanceof CElaboratedType) {
      // resolve chains of elaborated types
      return ((CElaboratedType) realType).getRealType(); // XXX with alignment
    }
    return realType;
  }

  /**
   * This method should be called only during parsing.
   */
  public void setRealType(CComplexType pRealType) {
    checkState(getRealType() == null);
    checkNotNull(pRealType);
    checkArgument(pRealType != this);
    checkArgument(pRealType.getKind() == kind);

    // all elaborated types are renamed such that they only match on the struct
    // name suffixed with the filename, when setting the realtype the name
    // may change to be only the struct name without the suffix
    checkArgument(name.contains(pRealType.getName()));
    realType = pRealType;
    name = realType.getName();
  }

  @Override
  public String toASTString(String pDeclarator) {
    checkNotNull(pDeclarator);
    StringBuilder lASTString = new StringBuilder();

    if (isConst()) {
      lASTString.append("const ");
    }
    if (isVolatile()) {
      lASTString.append("volatile ");
    }

    lASTString.append(kind.toASTString());
    lASTString.append(" ");
    if (alignment != null) {
      lASTString.append("__attribute__ ((__aligned__(");
      lASTString.append(alignment);
      lASTString.append("))) ");
    }
    lASTString.append(name);
    lASTString.append(" ");
    lASTString.append(pDeclarator);

    return lASTString.toString();
  }

  @Override
  public String toString() {
    return getKind().toASTString() + " " + getName();
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
  public @Nullable Integer getAlignment() {
    return alignment;
  }

  @Override
  public boolean isPacked() {
    return realType != null && realType.isPacked();
  }

  @Override
  public Membership getMembership() {
    return member;
  }

  @Override
  public boolean isIncomplete() {
    if (realType == null) {
      return kind != ComplexTypeKind.ENUM; // enums are always complete
    } else {
      return realType.isIncomplete();
    }
  }

  @Override
  public <R, X extends Exception> R accept(CTypeVisitor<R, X> pVisitor) throws X {
    return pVisitor.visit(this);
  }

  @Override
  public int hashCode() {
    if (hashCache == 0) {
      hashCache = Objects.hash(isConst, isVolatile, kind, name, realType);
    }
    return hashCache;
  }

  /**
   * Be careful, this method compares the CType as it is to the given object,
   * typedefs won't be resolved. If you want to compare the type without having
   * typedefs in it use #getCanonicalType().equals()
   */
  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }

    if (!(obj instanceof CElaboratedType)) {
      return false;
    }

    CElaboratedType other = (CElaboratedType) obj;

    return isConst == other.isConst
        && isVolatile == other.isVolatile
        && member == other.member
        && kind == other.kind
        && Objects.equals(alignment, other.alignment)
        && Objects.equals(name, other.name)
        && Objects.equals(realType, other.realType);
  }

  @Override
  public boolean equalsWithOrigName(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }

    if (!(obj instanceof CElaboratedType)) {
      return false;
    }

    CElaboratedType other = (CElaboratedType) obj;

    return isConst == other.isConst
        && isVolatile == other.isVolatile
        && member == other.member
        && kind == other.kind
        && Objects.equals(alignment, other.alignment)
        && (Objects.equals(name, other.name) || (origName.isEmpty() && other.origName.isEmpty()))
        && Objects.equals(realType, other.realType);
  }

  @Override
  public CType getCanonicalType() {
    return getCanonicalType(false, false);
  }

  @Override
  public CType getCanonicalType(boolean pForceConst, boolean pForceVolatile) {
    if (realType == null) {
      if ((isConst == pForceConst) && (isVolatile == pForceVolatile)) {
        return this;
      }
      return new CElaboratedType(isConst || pForceConst, isVolatile || pForceVolatile,
          alignment, member, kind, name, origName, null);
    } else {
      CType t = realType.getCanonicalType(isConst || pForceConst, isVolatile || pForceVolatile);
      if (alignment != null) {
        t = CTypes.withAttributes(t, alignment);
      }
      if (kind != ComplexTypeKind.ENUM) {
        t = CTypes.asMember(t, member);
      }
      return t;
    }
  }
}
