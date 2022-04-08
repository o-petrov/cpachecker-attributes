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
public class CTypedefToStringAlignmentTest {

  private static final String VAR = "var";

  private static final CType FOO =
      new CTypedefType(false, false, Alignment.NO_SPECIFIERS, "foo", CNumericTypes.INT);
  private static final CType FOOC =
      new CTypedefType(
          false,
          false,
          Alignment.NO_SPECIFIERS,
          "fooc",
          CNumericTypes.INT.getCanonicalType(true, false));
  private static final CType FOOA =
      new CTypedefType(false, false, Alignment.ofType(1), "fooa", CNumericTypes.INT);

  private static String aligned(int a) {
    return "__attribute__((__aligned__(" + a + ")))";
  }

  @Parameters(name = "{0}; {1}; // {2}")
  public static Object[][] types() {
    return new Object[][] {
      { // declare var as foo [int]
        "typedef int foo", "foo var", FOO,
      },
      { // declare var as 1-aligned foo [int]
        "typedef int foo", "foo var " + aligned(1), CTypes.withAlignment(FOO, Alignment.ofVar(1)),
      },
      { // declare var as alignas-16 foo [int]
        "typedef int foo",
        "_Alignas(16) foo var",
        CTypes.withAlignment(FOO, Alignment.ofAlignas(16)),
      },
      { // declare var as alignas-16 2-aligned foo [int]
        "typedef int foo",
        "_Alignas(16) foo var " + aligned(2),
        CTypes.withAlignment(FOO, Alignment.ofAlignas(16).withVarAligned(2)),
      },

      //
      { // declare var as fooc [const int]
        "typedef const int fooc", "fooc var", FOOC,
      },
      { // declare var as 2-aligned fooc [const int]
        "typedef const int fooc",
        "fooc var " + aligned(2),
        CTypes.withAlignment(FOOC, Alignment.ofVar(2)),
      },
      { // declare var as alignas-16 fooc [const int]
        "typedef const int fooc",
        "_Alignas(16) fooc var",
        CTypes.withAlignment(FOOC, Alignment.ofAlignas(16)),
      },
      { // declare var as alignas-16 2-aligned fooc [int]
        "typedef const int fooc",
        "_Alignas(16) fooc var " + aligned(2),
        CTypes.withAlignment(FOOC, Alignment.ofAlignas(16).withVarAligned(2)),
      },

      //
      { // declare var as fooa [1-aligned int]
        "typedef int " + aligned(1) + " fooa", "fooa var", FOOA,
      },
      { // declare var as 2-aligned fooa [1-aligned int]
        "typedef int " + aligned(1) + " fooa",
        "fooa var " + aligned(2),
        CTypes.withAlignment(FOOA, Alignment.ofVar(2)),
      },
      { // declare var as alignas-16 fooa [1-aligned int]
        "typedef int " + aligned(1) + " fooa",
        "_Alignas(16) fooa var",
        CTypes.withAlignment(FOOA, Alignment.ofAlignas(16)),
      },
      { // declare var as alignas-16 2-aligned fooa [1-aligned int]
        "typedef int " + aligned(1) + " fooa",
        "_Alignas(16) fooa var " + aligned(2),
        CTypes.withAlignment(FOOA, Alignment.ofAlignas(16).withVarAligned(2)),
      },
    };
  }

  @Parameter(0)
  public String typedef;

  @Parameter(1)
  public String declaration;

  @Parameter(2)
  public CTypedefType type;

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
    assertThat(type.toASTString(VAR)).isEqualTo(declaration);
  }

  @Test
  public void testParse() throws CParserException, InterruptedException {
    CTypedefType parsed =
        (CTypedefType)
            parser
                .parseString(Path.of("dummy"), typedef + "; " + declaration + ";")
                .getGlobalDeclarations()
                .get(1)
                .getFirst()
                .getType();
    assertThat(parsed.getRealType().getCanonicalType())
        .isEqualTo(type.getRealType().getCanonicalType());
    assertThat(parsed.getCanonicalType()).isEqualTo(type.getCanonicalType());
  }
}
