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
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import java.util.ArrayList;
import java.util.Objects;
import org.sosy_lab.cpachecker.cfa.types.c.CComplexType.ComplexTypeKind;

/** Instances of this class represent C bit-field types. */
public class CBitFieldType implements CType {

  private static final long serialVersionUID = 1L;

  private final CType type;

  private final int bitFieldSize;

  private Alignment alignment;

  /**
   * Creates a new bit-field type.
   *
   * @param pBitFieldType the base type of the bit field. The only allowed values are
   *     <ul>
   *       <li>{@link CSimpleType} with basic types {@link CBasicType#BOOL}, {@link
   *           CBasicType#CHAR}, and {@link CBasicType#INT},
   *       <li>{@link CEnumType}, and
   *       <li>{@link CElaboratedType} with {@link CElaboratedType#getKind()} == {@link
   *           ComplexTypeKind#ENUM}
   *     </ul>
   *
   * @param pBitFieldSize the length of the field in bits. Must not be negative.
   * @throws IllegalArgumentException if the given type is not a valid basic type for a bit field or
   *     if the given size is negative.
   */
  public CBitFieldType(CType pBitFieldType, int pBitFieldSize) {
    this(pBitFieldType, pBitFieldSize, Alignment.NO_SPECIFIERS);
  }

  public CBitFieldType(CType pBitFieldType, int pBitFieldSize, Alignment pAlignment) {
    type = checkType(pBitFieldType);
    Preconditions.checkArgument(
        pBitFieldSize >= 0, "Bit-field size must not be negative, but was %s", pBitFieldSize);
    bitFieldSize = pBitFieldSize;
    Preconditions.checkArgument(
        pAlignment.getTypeAligned() == Alignment.NO_SPECIFIER,
        "Bitfield can not be type-aligned (got %s)",
        pAlignment);
    alignment = Preconditions.checkNotNull(pAlignment);
  }

  private CType checkType(CType pBitFieldType) {
    CType canonicalType = pBitFieldType.getCanonicalType();
    if (canonicalType instanceof CSimpleType) {
      CSimpleType simpleType = (CSimpleType) canonicalType;
      CBasicType basicType = simpleType.getType();
      switch (basicType) {
        case BOOL:
        case CHAR:
        case INT:
          return pBitFieldType;
        default:
          break;
      }
    } else if (canonicalType instanceof CEnumType) {
      return pBitFieldType;
    } else if (canonicalType instanceof CElaboratedType) {
      CElaboratedType elaboratedType = (CElaboratedType) canonicalType;
      if (elaboratedType.getKind() == ComplexTypeKind.ENUM) {
        return pBitFieldType;
      }
    }
    throw new IllegalArgumentException("Not a valid bit-field type: " + pBitFieldType);
  }

  @Override
  public String toASTString(String pDeclarator) {
    checkNotNull(pDeclarator);
    if (bitFieldSize == 0) {
      // bit-field types are valid only in fields, and zero-width bit fields need to be anonymous
      pDeclarator = "";
    }

    ArrayList<String> parts = new ArrayList<>();
    parts.add(Strings.emptyToNull(alignment.stringAlignas()));
    parts.add(type.toASTString(pDeclarator));
    parts.add(":");
    parts.add(String.valueOf(bitFieldSize));
    parts.add(Strings.emptyToNull(alignment.stringVarAligned()));
    return Joiner.on(' ').skipNulls().join(parts);
  }

  @Override
  public boolean isConst() {
    return type.isConst();
  }

  @Override
  public boolean isVolatile() {
    return type.isVolatile();
  }

  @Override
  public Alignment getAlignment() {
    return alignment;
  }

  @Override
  public boolean isIncomplete() {
    return type.isIncomplete();
  }

  @Override
  public <R, X extends Exception> R accept(CTypeVisitor<R, X> pVisitor) throws X {
    return pVisitor.visit(this);
  }

  @Override
  public CType getCanonicalType() {
    return getCanonicalType(false, false);
  }

  @Override
  public CType getCanonicalType(boolean pForceConst, boolean pForceVolatile) {
    CType canonicalBitFieldType = type.getCanonicalType(pForceConst, pForceVolatile);
    if (type == canonicalBitFieldType) {
      return this;
    }
    return new CBitFieldType(canonicalBitFieldType, bitFieldSize, alignment);
  }

  /**
   * Gets the size of the bit field in bits.
   *
   * @return the size of the bit field in bits.
   */
  public int getBitFieldSize() {
    return bitFieldSize;
  }

  /**
   * Gets the base type of the bit field.
   *
   * @return the base type of the bit field.
   */
  public CType getType() {
    return type;
  }

  @Override
  public String toString() {
    String result = toASTString("");
    if (alignment.getTypeAligned() == Alignment.NO_SPECIFIER) {
      return result;
    }
    return result + ' ' + alignment.stringTypeAlignedAsComment();
  }

  @Override
  public int hashCode() {
    return Objects.hash(bitFieldSize, alignment, type);
  }

  @Override
  public boolean equals(Object pObj) {
    if (pObj == this) {
      return true;
    }
    if (pObj instanceof CBitFieldType) {
      CBitFieldType other = (CBitFieldType) pObj;
      return bitFieldSize == other.bitFieldSize
          && alignment.equals(other.alignment)
          && type.equals(other.type);
    }
    return false;
  }
}
