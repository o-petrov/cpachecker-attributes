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
import static com.google.common.collect.Iterables.transform;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class CCompositeType implements CComplexType {

  private static final long serialVersionUID = -839957929135012583L;
  private final CComplexType.ComplexTypeKind kind;
  private @Nullable List<CCompositeTypeMemberDeclaration> members = null;
  private final String name;
  private final String origName;
  private final boolean isConst;
  private final boolean isVolatile;
  private final Alignment alignment;
  private final boolean isPacked;

  public CCompositeType(
      boolean pConst,
      boolean pVolatile,
      CComplexType.ComplexTypeKind pKind,
      String pName,
      String pOrigName) {
    this(pConst, pVolatile, Alignment.NO_SPECIFIERS, false, pKind, pName, pOrigName);
  }

  public CCompositeType(
      boolean pConst,
      boolean pVolatile,
      Alignment pAlignment,
      boolean pPacked,
      CComplexType.ComplexTypeKind pKind,
      List<CCompositeTypeMemberDeclaration> pMembers,
      String pName,
      String pOrigName) {
    this(pConst, pVolatile, pAlignment, pPacked, pKind, pName, pOrigName);
    checkMembers(pMembers);
    members = ImmutableList.copyOf(pMembers);
  }

  public CCompositeType(
      boolean pConst,
      boolean pVolatile,
      CComplexType.ComplexTypeKind pKind,
      List<CCompositeTypeMemberDeclaration> pMembers,
      String pName,
      String pOrigName) {
    this(pConst, pVolatile, Alignment.NO_SPECIFIERS, false, pKind, pName, pOrigName);
    checkMembers(pMembers);
    members = ImmutableList.copyOf(pMembers);
  }

  public CCompositeType(
      boolean pConst,
      boolean pVolatile,
      Alignment pAlignment,
      boolean pPacked,
      ComplexTypeKind pKind,
      String pName,
      String pOrigName) {
    checkNotNull(pKind);
    checkArgument(pKind == ComplexTypeKind.STRUCT || pKind == ComplexTypeKind.UNION);
    isConst = pConst;
    isVolatile = pVolatile;
    alignment = checkNotNull(pAlignment);
    isPacked = pPacked;
    kind = pKind;
    name = pName.intern();
    origName = pOrigName.intern();
  }

  private void checkMembers(List<CCompositeTypeMemberDeclaration> pMembers) {
    for (Iterator<CCompositeTypeMemberDeclaration> it = pMembers.iterator(); it.hasNext(); ) {
      CCompositeTypeMemberDeclaration member = it.next();
      if (member.getType().isIncomplete()) {
        checkArgument(kind == ComplexTypeKind.STRUCT, "incomplete member %s in %s", member, this);
        checkArgument(
            !it.hasNext(), "incomplete member %s in non-last position of %s", member, this);
        checkArgument(
            member.getType().getCanonicalType() instanceof CArrayType,
            "incomplete non-array member %s in last position of %s",
            member,
            this);
      }
    }
  }

  @Override
  public CComplexType.ComplexTypeKind getKind() {
    return kind;
  }

  public List<CCompositeTypeMemberDeclaration> getMembers() {
    checkState(members != null, "list of CCompositeType members not yet initialized");
    return members;
  }

  public void setMembers(List<CCompositeTypeMemberDeclaration> list) {
    checkState(members == null, "list of CCompositeType members already initialized");
    checkMembers(list);
    members = ImmutableList.copyOf(list);
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String getQualifiedName() {
    return (kind.toASTString() + " " + name).trim();
  }

  @Override
  public String getOrigName() {
    return origName;
  }

  @Override
  public boolean isIncomplete() {
    return false;
  }

  @Override
  public String toString() {
    return toASTString("", false);
  }

  @Override
  public String toASTString(String pDeclarator) {
    return toASTString(pDeclarator, true);
  }

  public String toASTString(String pDeclarator, boolean withMembers) {
    checkNotNull(pDeclarator);
    ArrayList<String> parts = new ArrayList<>();
    parts.add(Strings.emptyToNull(alignment.stringAlignas()));
    if (isConst()) {
      parts.add("const");
    }
    if (isVolatile()) {
      parts.add("volatile");
    }
    parts.add(kind.toASTString());
    parts.add(name);

    if (withMembers) {
      if (members == null) {
        parts.add("/* missing member initialization */");
      } else {
        parts.add(
            "{\n  "
                + Joiner.on(",\n  ")
                    .join(transform(members, CCompositeTypeMemberDeclaration::toASTString))
                + "\n}");
      }
    }

    if (isPacked) {
      parts.add("__attribute__((__packed__))");
    }

    parts.add(Strings.emptyToNull(alignment.stringTypeAligned()));
    parts.add(pDeclarator);
    parts.add(Strings.emptyToNull(alignment.stringVarAligned()));
    return Joiner.on(' ').skipNulls().join(parts);
  }

  /**
   * This is the declaration of a member of a composite type. It contains a type and an optional
   * name.
   */
  public static final class CCompositeTypeMemberDeclaration implements Serializable {

    private static final long serialVersionUID = 8647666228796784933L;
    private final CType type;
    private final String name;

    public CCompositeTypeMemberDeclaration(CType pType, String pName) {

      type = checkNotNull(pType);
      name = pName;
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 7;
      result = prime * result + Objects.hashCode(name);
      result = prime * result + Objects.hashCode(type);
      return result;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      if (this == obj) {
        return true;
      }
      if (obj == null) {
        return false;
      }
      if (getClass() != obj.getClass()) {
        return false;
      }
      CCompositeTypeMemberDeclaration other = (CCompositeTypeMemberDeclaration) obj;
      return Objects.equals(name, other.name)
          && type.getCanonicalType().equals(other.type.getCanonicalType());
    }

    public CType getType() {
      return type;
    }

    public String getName() {
      return name;
    }

    public String toASTString() {
      return getType().toASTString(Strings.nullToEmpty(getName())) + ";";
    }

    @Override
    public String toString() {
      return toASTString();
    }
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

  @Override
  public boolean isPacked() {
    return isPacked;
  }

  @Override
  public <R, X extends Exception> R accept(CTypeVisitor<R, X> pVisitor) throws X {
    return pVisitor.visit(this);
  }

  @Override
  public int hashCode() {
    return Objects.hash(isConst, isVolatile, alignment, isPacked, kind, name);
  }

  /**
   * Be careful, this method compares the CType as it is to the given object, typedefs won't be
   * resolved. If you want to compare the type without having typedefs in it use
   * #getCanonicalType().equals()
   */
  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }

    if (!(obj instanceof CCompositeType)) {
      return false;
    }

    CCompositeType other = (CCompositeType) obj;

    return isConst == other.isConst
        && isVolatile == other.isVolatile
        && alignment.equals(other.alignment)
        && isPacked == other.isPacked
        && kind == other.kind
        && Objects.equals(name, other.name);
  }

  @Override
  public boolean equalsWithOrigName(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }

    if (!(obj instanceof CCompositeType)) {
      return false;
    }

    CCompositeType other = (CCompositeType) obj;

    return isConst == other.isConst
        && isVolatile == other.isVolatile
        && alignment.equals(other.alignment)
        && isPacked == other.isPacked
        && kind == other.kind
        && (Objects.equals(name, other.name) || (origName.isEmpty() && other.origName.isEmpty()));
  }

  @Override
  public CCompositeType getCanonicalType() {
    return getCanonicalType(false, false);
  }

  @Override
  public CCompositeType getCanonicalType(boolean pForceConst, boolean pForceVolatile) {
    if ((isConst == pForceConst) && (isVolatile == pForceVolatile)) {
      return this;
    }
    CCompositeType result =
        new CCompositeType(
            isConst || pForceConst,
            isVolatile || pForceVolatile,
            alignment,
            isPacked,
            kind,
            name,
            origName);
    if (members != null) {
      result.setMembers(members);
    }
    return result;
  }

  @Override
  public CType copyWithPacked(boolean pPacked) {
    if (isPacked == pPacked) {
      return this;
    }
    CCompositeType result =
        new CCompositeType(isConst, isVolatile, alignment, pPacked, kind, name, origName);
    if (members != null) {
      List<CCompositeTypeMemberDeclaration> newMembers = new ArrayList<>();
      for (CCompositeTypeMemberDeclaration m : members) {
        CType mType = m.getType();
        Alignment mAlign = mType.getAlignment();
        mType = CTypes.overrideAlignment(mType, mAlign.withInsidePacked(pPacked));
        newMembers.add(new CCompositeTypeMemberDeclaration(mType, m.getName()));
      }
      result.setMembers(newMembers);
    }
    return result;
  }
}
