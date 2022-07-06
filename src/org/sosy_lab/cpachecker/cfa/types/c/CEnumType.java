// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.types.c;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Iterables.transform;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.cpachecker.cfa.ast.AbstractSimpleDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.FileLocation;
import org.sosy_lab.cpachecker.cfa.ast.c.CAstNodeVisitor;
import org.sosy_lab.cpachecker.cfa.ast.c.CSimpleDeclaration;
import org.sosy_lab.cpachecker.cfa.ast.c.CSimpleDeclarationVisitor;
import org.sosy_lab.cpachecker.cfa.types.Type;

public final class CEnumType implements CComplexType {

  private static final long serialVersionUID = -986078271714119880L;

  private final ImmutableList<CEnumerator> enumerators;
  private final String name;
  private final String origName;
  private final boolean isConst;
  private final boolean isVolatile;
  private final Alignment alignment;
  private final boolean isPacked;
  private CSimpleType integerType;
  private int hashCache = 0;

  public CEnumType(
      boolean pConst,
      boolean pVolatile,
      Alignment pAlignment,
      boolean pPacked,
      CSimpleType pIntegerType,
      List<CEnumerator> pEnumerators,
      String pName,
      String pOrigName) {
    isConst = pConst;
    isVolatile = pVolatile;
    alignment = checkNotNull(pAlignment);
    isPacked = pPacked;
    integerType = checkNotNull(pIntegerType);
    enumerators = ImmutableList.copyOf(pEnumerators);
    name = pName.intern();
    origName = pOrigName.intern();
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
  public boolean isIncomplete() {
    return false;
  }

  @Override
  public Alignment getAlignment() {
    return alignment;
  }

  @Override
  public boolean isPacked() {
    return isPacked;
  }

  /**
   * §6.7.2.2 (4) Each enumerated type shall be compatible with char, a signed integer type, or an
   * unsigned integer type. The choice of type is implementation-defined, but shall be capable of
   * representing the values of all the members of the enumeration.
   *
   * <p>GCC selects type that can represent all enumerator values. If enumerated type has no
   * negative enumerators, GCC selects unsigned type. For a packed enum GCC selects smallest
   * possible type from char, short, int, long, long long. For a not packed enum GCC selects type
   * from int, long, long long.
   *
   * <p>Enumerator of any enumerated type has type int if its value is inside int bounds. GCC allows
   * enumerators with values out of int bounds (breaks §6.7.2.2 (2)). The type for such enumerator
   * is the compatible integer type of the enum.
   *
   * <p>Note that types of enumerators may be either int or the underlying type of the enum and so
   * may differ from each other and the enum compatible type.
   *
   * @return integer type compatible with this enum
   */
  public CSimpleType getIntegerType() {
    return integerType;
  }

  public ImmutableList<CEnumerator> getEnumerators() {
    return enumerators;
  }

  @Override
  public ComplexTypeKind getKind() {
    return ComplexTypeKind.ENUM;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String getQualifiedName() {
    return ("enum " + name).trim();
  }

  @Override
  public String getOrigName() {
    return origName;
  }

  @Override
  public String toASTString(String pDeclarator) {
    checkNotNull(pDeclarator);
    ArrayList<String> parts = new ArrayList<>();
    parts.add(Strings.emptyToNull(alignment.stringAlignas()));
    if (isConst()) {
      parts.add("const");
    }
    if (isVolatile()) {
      parts.add("volatile");
    }
    parts.add("enum");
    parts.add(name);

    if (!pDeclarator.isEmpty()) {
      parts.add(
          "{\n  "
              + Joiner.on(",\n  ").join(transform(enumerators, CEnumerator::toASTString))
              + "\n}");
    }

    if (isPacked) {
      parts.add("__attribute__((__packed__))");
    }

    parts.add(Strings.emptyToNull(alignment.stringTypeAligned()));
    parts.add(Strings.emptyToNull(pDeclarator));
    parts.add(Strings.emptyToNull(alignment.stringVarAligned()));
    return Joiner.on(' ').skipNulls().join(parts);
  }

  @Override
  public String toString() {
    return toASTString("");
  }

  public static final class CEnumerator extends AbstractSimpleDeclaration
      implements CSimpleDeclaration {

    private static final long serialVersionUID = -2526725372840523651L;

    private final @Nullable BigInteger value;
    private @Nullable CEnumType enumType;
    private final String qualifiedName;

    public CEnumerator(
        final FileLocation pFileLocation,
        final String pName,
        final String pQualifiedName,
        final @Nullable CType pType,
        final @Nullable BigInteger pValue) {
      super(pFileLocation, pType, pName);

      checkNotNull(pName);
      value = pValue;
      qualifiedName = checkNotNull(pQualifiedName);
    }

    /** Get the enum that declared this enumerator. */
    public CEnumType getEnum() {
      return enumType;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }

      if (!(obj instanceof CEnumerator) || !super.equals(obj)) {
        return false;
      }

      CEnumerator other = (CEnumerator) obj;

      return Objects.equals(value, other.value) && qualifiedName.equals(other.qualifiedName);
      // do not compare the enumType, comparing it with == is wrong because types which
      // are the same but not identical would lead to wrong results
      // comparing it with equals is no good choice, too. This would lead to a stack
      // overflow
      //  && (enumType == other.enumType);
    }

    @Override
    public int hashCode() {
      return Objects.hash(value, enumType, qualifiedName) * 31 + super.hashCode();
    }

    /** This method should be called only during parsing. */
    public void setEnum(CEnumType pEnumType) {
      checkState(enumType == null);
      enumType = checkNotNull(pEnumType);
    }

    @Override
    public void setType(Type pType) {
      super.setType(checkNotNull(pType));
    }

    @Override
    public String getQualifiedName() {
      return qualifiedName;
    }

    @Override
    public CType getType() {
      return (CType) super.getType();
    }

    public @Nullable BigInteger getValue() {
      checkState(value != null, "Need to check hasValue() before calling getValue()");
      return value;
    }

    public boolean hasValue() {
      return value != null;
    }

    @Override
    public String toASTString() {
      return getQualifiedName().replace("::", "__") + (hasValue() ? " = " + value : "");
    }

    @Override
    public <R, X extends Exception> R accept(CSimpleDeclarationVisitor<R, X> pV) throws X {
      return pV.visit(this);
    }

    @Override
    public <R, X extends Exception> R accept(CAstNodeVisitor<R, X> pV) throws X {
      return pV.visit(this);
    }
  }

  @Override
  public <R, X extends Exception> R accept(CTypeVisitor<R, X> pVisitor) throws X {
    return pVisitor.visit(this);
  }

  @Override
  public int hashCode() {
    if (hashCache == 0) {
      hashCache = Objects.hash(isConst, isVolatile, name, alignment, isPacked);
    }
    return hashCache;
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

    if (!(obj instanceof CEnumType)) {
      return false;
    }

    CEnumType other = (CEnumType) obj;

    return isConst == other.isConst
        && isVolatile == other.isVolatile
        && alignment.equals(other.alignment)
        && isPacked == other.isPacked
        && integerType.equals(other.integerType)
        && Objects.equals(name, other.name)
        && Objects.equals(enumerators, other.enumerators);
  }

  @Override
  public boolean equalsWithOrigName(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }

    if (!(obj instanceof CEnumType)) {
      return false;
    }

    CEnumType other = (CEnumType) obj;

    return isConst == other.isConst
        && isVolatile == other.isVolatile
        && alignment.equals(other.alignment)
        && isPacked == other.isPacked
        && integerType.equals(other.integerType)
        && (Objects.equals(name, other.name) || (origName.isEmpty() && other.origName.isEmpty()))
        && Objects.equals(enumerators, other.enumerators);
  }

  @Override
  public CEnumType getCanonicalType() {
    return getCanonicalType(false, false);
  }

  @Override
  public CEnumType getCanonicalType(boolean pForceConst, boolean pForceVolatile) {
    if ((isConst == pForceConst) && (isVolatile == pForceVolatile)) {
      return this;
    }
    return new CEnumType(
        isConst || pForceConst,
        isVolatile || pForceVolatile,
        alignment,
        isPacked,
        integerType,
        enumerators,
        name,
        origName);
  }

  @Override
  public CType copyWithPacked(boolean pPacked) {
    if (isPacked == pPacked) {
      return this;
    }
    return new CEnumType(
        isConst, isVolatile, alignment, pPacked, integerType, enumerators, name, origName);
  }
}
