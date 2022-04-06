// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.types.c;

import static com.google.common.truth.Truth.assertThat;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.file.Path;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.CParser;
import org.sosy_lab.cpachecker.cfa.parser.Parsers;
import org.sosy_lab.cpachecker.cfa.parser.Parsers.EclipseCParserOptions;
import org.sosy_lab.cpachecker.cfa.types.MachineModel;
import org.sosy_lab.cpachecker.exceptions.CParserException;

@RunWith(Parameterized.class)
@SuppressFBWarnings(
    value = "NP_NONNULL_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR",
    justification = "Fields are filled by parameterization of JUnit")
public class CTypeToStringAlignmentTest {

  private static final String VAR = "var";

  private static final CType CONST_VOLATILE_INT =
      new CSimpleType(true, true, Alignment.NO_SPECIFIERS, CBasicType.INT, false, false, false, false, false, false, false);

  private static String aligned(int a) {
    return "__attribute__((__aligned__(" + a + ")))";
  }

  @Parameters(name = "{0} [{1}]")
  public static Object[][] types() {
    return new Object[][] {
      // NUMERICS
      { // declare var as 1-aligned int
        "int " + aligned(1) + " var", CTypes.withAlignment(CNumericTypes.INT, Alignment.ofVar(1)),
      },
      { // declare var as const volatile 2-aligned int
        "const volatile int " + aligned(2) + " var",
        CTypes.withAlignment(CONST_VOLATILE_INT, Alignment.ofVar(2)),
      },
      { // declare var as const 4-aligned short
        "const signed short int " + aligned(4) + " var",
        CTypes.withAlignment(
            CNumericTypes.SHORT_INT.getCanonicalType(true, false), Alignment.ofVar(4)),
      },
      { // declare var as alignas-4 short
        "_Alignas(4) short int var",
        CTypes.withAlignment(CNumericTypes.SHORT_INT, Alignment.ofAlignas(4)),
      },
      { // declare var as alignas-4 short
        "_Alignas(4) unsigned char var",
        CTypes.withAlignment(CNumericTypes.UNSIGNED_CHAR, Alignment.ofAlignas(4)),
      },
      { // declare var as alignas-8 volatile unsigned int
        "_Alignas(8) volatile unsigned int var",
        CTypes.withAlignment(
            CNumericTypes.UNSIGNED_INT.getCanonicalType(false, true), Alignment.ofAlignas(8)),
      },
      { // declare var as alignas-16 2-aligned signed long long
        "_Alignas(16) signed long long int " + aligned(2) + " var",
        CTypes.withAlignment(
            CNumericTypes.SIGNED_LONG_LONG_INT, Alignment.ofAlignas(16).withVarAligned(2)),
      },
      { // declare var as 1-aligned long double
        "long double " + aligned(1) + " var",
        CTypes.withAlignment(CNumericTypes.LONG_DOUBLE, Alignment.ofVar(1)),
      },
      { // declare var as alignas-8 16-aligned float
        "_Alignas(8) float " + aligned(16) + " var",
        CTypes.withAlignment(CNumericTypes.FLOAT, Alignment.ofVar(16).withAlignas(8)),
      },

      // POINTERS
      { // declare var as 1-aligned pointer to char
        "char *var " + aligned(1),
        new CPointerType(false, false, Alignment.ofVar(1), CNumericTypes.CHAR),
      },
      { // declare var as 16-aligned pointer to unsigned short int
        "unsigned short int *var " + aligned(16),
        new CPointerType(false, false, Alignment.ofVar(16), CNumericTypes.UNSIGNED_SHORT_INT),
      },
      { // declare var as alignas-16 pointer to double
        "_Alignas(16) int *var",
        new CPointerType(false, false, Alignment.ofAlignas(16), CNumericTypes.INT),
      },
      { // declare var as 2-star-aligned pointer to long int
        "long int * " + aligned(2) + " var",
        new CPointerType(false, false, Alignment.ofType(2), CNumericTypes.LONG_INT),
      },
      { // declare var as 1-aligned 4-star-aligned alignas-16 pointer to long
        "_Alignas(16) long long int * " + aligned(4) + " var " + aligned(1),
        new CPointerType(false, false, new Alignment(4, 1, 16), CNumericTypes.LONG_LONG_INT),
      },
      { // declare var as const 16-star-aligned pointer to volatile long double
        "volatile float * const " + aligned(16) + " var",
        new CPointerType(
            true, false, Alignment.ofType(16), CNumericTypes.FLOAT.getCanonicalType(false, true)),
      },
      { // declare var as 1-star-aligned volatile pointer to
        // const 2-star-aligned pointer to double
        "double * const " + aligned(2) + " * volatile " + aligned(1) + " var",
        new CPointerType(
            false,
            true,
            Alignment.ofType(1),
            new CPointerType(true, false, Alignment.ofType(2), CNumericTypes.DOUBLE)),
      },
      { // declare alignas-16 var as 16-aligned 4-star-aligned pointer to
        // 2-star-aligned pointer to 1-star-aligned pointer to long double
        "_Alignas(16) long double * "
            + aligned(1)
            + " * "
            + aligned(2)
            + " * "
            + aligned(4)
            + " var "
            + aligned(16),
        new CPointerType(
            false,
            false,
            new Alignment(4, 16, 16),
            new CPointerType(
                false,
                false,
                Alignment.ofType(2),
                new CPointerType(false, false, Alignment.ofType(1), CNumericTypes.LONG_DOUBLE))),
      },
    };
  }

  @Parameter(0)
  public String stringRepr;

  @Parameter(1)
  public CType type;

  private static CParser parser;

  @BeforeClass
  public static void setupParser() {
    parser =
        Parsers.getCParser(
            LogManager.createTestLogManager(),
            new EclipseCParserOptions(),
            MachineModel.LINUX32,
            ShutdownNotifier.createDummy());
  }

  @Test
  public void testToString() {
    assertThat(type.toASTString(VAR)).isEqualTo(stringRepr);
  }

  @Test
  public void testParse() throws CParserException, InterruptedException {
    CType parsed =
        (CType)
            parser
                .parseString(Path.of("dummy"), stringRepr + ";")
                .getGlobalDeclarations()
                .get(0)
                .getFirst()
                .getType();
    assertThat(parsed.getCanonicalType()).isEqualTo(type.getCanonicalType());
  }
}
