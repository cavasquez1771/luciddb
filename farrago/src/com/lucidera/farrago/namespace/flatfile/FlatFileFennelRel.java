/*
// $Id$
// Farrago is an extensible data management system.
// Copyright (C) 2005-2005 LucidEra, Inc.
// Copyright (C) 2005-2005 The Eigenbase Project
// Portions Copyright (C) 2003-2005 John V. Sichi
//
// This program is free software; you can redistribute it and/or modify it
// under the terms of the GNU General Public License as published by the Free
// Software Foundation; either version 2 of the License, or (at your option)
// any later version approved by The Eigenbase Project.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
package com.lucidera.farrago.namespace.flatfile;

import java.sql.*;
import java.util.*;

import junit.framework.*;

import net.sf.farrago.catalog.*;
import net.sf.farrago.fem.fennel.*;
import net.sf.farrago.query.*;
import net.sf.farrago.util.*;

import openjava.mop.*;
import openjava.ptree.*;

import org.eigenbase.oj.rel.*;
import org.eigenbase.oj.stmt.*;
import org.eigenbase.oj.util.*;
import org.eigenbase.rel.*;
import org.eigenbase.rel.jdbc.*;
import org.eigenbase.relopt.*;
import org.eigenbase.reltype.*;
import org.eigenbase.rex.*;
import org.eigenbase.sql.*;
import org.eigenbase.sql.type.*;
import org.eigenbase.util.*;

import com.disruptivetech.farrago.calc.*;

/**
 * FlatFileFennelRel provides a flatfile implementation for
 * {@link TableAccessRel} with {@link FennelRel#FENNEL_EXEC_CONVENTION}.
 *
 * @author John V. Pham
 * @version $Id$
 */
class FlatFileFennelRel extends TableAccessRelBase implements FennelRel
{
    //~ Instance fields -------------------------------------------------------

    private FlatFileColumnSet columnSet;

    //~ Constructors ----------------------------------------------------------

    FlatFileFennelRel(
        FlatFileColumnSet columnSet,
        RelOptCluster cluster,
        RelOptConnection connection)
    {
        super(
            cluster, new RelTraitSet(FENNEL_EXEC_CONVENTION), columnSet,
            connection);
        this.columnSet = columnSet;
    }

    //~ Methods ---------------------------------------------------------------

    // implement FennelRel
    public Object implementFennelChild(FennelRelImplementor implementor)
    {
        return Literal.constantNull();
    }

    // implement FennelRel
    public FemExecutionStreamDef toStreamDef(FennelRelImplementor implementor)
    {
        final FarragoRepos repos = FennelRelUtil.getRepos(this);
        FlatFileParams params = columnSet.getParams();
        
        FemFlatFileTupleStreamDef streamDef =
            repos.newFemFlatFileTupleStreamDef();
        streamDef.setDataFilePath(columnSet.getFilename());
        if (params.getWithErrorLogging()) {
            // TODO: log errors to file
            //streamDef.setErrorFilePath();
        }
        streamDef.setHasHeader(params.getWithHeader());
        streamDef.setNumRowsScan(params.getNumRowsScan());
        streamDef.setFieldDelimiter(
            Character.toString(params.getFieldDelimiter()));
        streamDef.setRowDelimiter(
            Character.toString(params.getLineDelimiter()));
        streamDef.setQuoteCharacter(
            Character.toString(params.getQuoteChar()));
        streamDef.setEscapeCharacter(
            Character.toString(params.getEscapeChar()));
        streamDef.setCalcProgram(
            ProgramWriter.write(columnSet.getRowType()));
        
        return streamDef;
    }

    // implement FennelRel
    public RelFieldCollation [] getCollations()
    {
        // trivially sorted
        return new RelFieldCollation [] { new RelFieldCollation(0) };
    }

    // implement RelNode
    public Object clone()
    {
        FlatFileFennelRel clone =
            new FlatFileFennelRel(columnSet, getCluster(), connection);
        clone.inheritTraitsFrom(this);
        return clone;
    }

    //~ Inner Classes ---------------------------------------------------------

    /**
     * Constructs a {@link Calculator} program for translating text
     * from a flat file into typed data. It is assumed that the text
     * has already been processed for quoting and escape characters.
     */
    static public class ProgramWriter 
    {
        /**
         * Given the description of the expected data types,
         * generates a program for converting text into typed data.
         *
         * <p>
         * 
         * First this method infers the description of text columns
         * required to read the exptected data values. Then it
         * constructs the casts necessary to perform data conversion.
         * Date conversions may require special functions.
         *
         * <p>
         *
         * It relies on a {@link RexToCalcTranslator} to convert the
         * casts into a calculator program.
         */
        public static String write(RelDataType rowType) 
        {
            RelDataTypeFactory typeFactory = new SqlTypeFactoryImpl();
            RexBuilder rexBuilder = new RexBuilder(typeFactory);

            assert(rowType.isStruct());
            RelDataTypeField[] targetTypes = rowType.getFields();
            RelDataType[] sourceTypes = new RelDataType[targetTypes.length];
            String[] sourceNames = new String[targetTypes.length];
            RexNode[] castExps = new RexNode[targetTypes.length];

            for (int i = 0; i < targetTypes.length; i++) {
                RelDataType targetType = targetTypes[i].getType();
                sourceTypes[i] = getTextType(typeFactory, targetType);
                sourceNames[i] = "col" + i;
                RexNode sourceNode =
                    rexBuilder.makeInputRef(sourceTypes[i], i);
                // TODO: call a dedicated function for conversion
                castExps[i] = rexBuilder.makeCast(targetType, sourceNode);
            }
            RelDataType inputRowType =
                typeFactory.createStructType(sourceTypes, sourceNames);
            
            RexToCalcTranslator translator =
                new RexToCalcTranslator(rexBuilder);
            return translator.getProgram(inputRowType, castExps, null);
        }

        /**
         * Converts a SQL type into a type that can be used by
         * a Fennel FlatFileExecStream to read files.
         */
        public static RelDataType getTextType(
            RelDataTypeFactory factory,
            RelDataType sqlType) 
        {
            int length = 255;
            switch (sqlType.getSqlTypeName().getOrdinal()) {
            case SqlTypeName.Char_ordinal:
            case SqlTypeName.Varchar_ordinal:
                length = sqlType.getPrecision();
                break;
            case SqlTypeName.Bigint_ordinal:
            case SqlTypeName.Boolean_ordinal:
            case SqlTypeName.Date_ordinal:
            case SqlTypeName.Double_ordinal:
            case SqlTypeName.Float_ordinal:
            case SqlTypeName.Integer_ordinal:
            case SqlTypeName.Real_ordinal:
            case SqlTypeName.Smallint_ordinal:
            case SqlTypeName.Time_ordinal:
            case SqlTypeName.Timestamp_ordinal:
            case SqlTypeName.Tinyint_ordinal:
                break;
            case SqlTypeName.Binary_ordinal:
            case SqlTypeName.Decimal_ordinal:
            case SqlTypeName.IntervalDayTime_ordinal:
            case SqlTypeName.IntervalYearMonth_ordinal:
            case SqlTypeName.Multiset_ordinal:
            case SqlTypeName.Null_ordinal:
            case SqlTypeName.Row_ordinal:
            case SqlTypeName.Structured_ordinal:
            case SqlTypeName.Symbol_ordinal:
            case SqlTypeName.Varbinary_ordinal:
            default:
                // unsupported for flat files
                assert(false) : "Type is unsupported for flat files: " +
                    sqlType.getSqlTypeName();
            }
            return factory.createSqlType(SqlTypeName.Varchar, length);
        }
    }

    /**
     * Tests the {@link ProgramWriter}
     */
    static public class Tester extends TestCase 
    {
        public Tester(String name)
        {
            super(name);
        }

        public void testProgram() 
        {
            RelDataTypeFactory typeFactory = new SqlTypeFactoryImpl();
            RelDataType fieldType =
                typeFactory.createSqlType(SqlTypeName.Integer);
            RelDataType[] fieldTypes = new RelDataType[1];
            String[] fieldNames = new String[1];
            fieldTypes[0] = fieldType;
            fieldNames[0] = "col1";
            RelDataType rowType =
                typeFactory.createStructType(fieldTypes, fieldNames);
            
            String program = ProgramWriter.write(rowType);
            assertEquals(
                "O s4;\n" +
                "I vc,255;\n" +
                "L s8, s4, bo;\n" +
                "C bo, bo, vc,5;\n" +
                "V 1, 0, 0x3232303034 /* 22004 */;\n" +
                "T;\n" +
                "CALL 'castA(L0, I0) /* 0: CAST($0):BIGINT NOT NULL */;\n" +
                "CAST L1, L0 /* 1: CAST(CAST($0):BIGINT NOT NULL):INTEGER NOT NULL CAST($0):INTEGER NOT NULL */;\n" +
                "ISNULL L2, L1 /* 2: */;\n" +
                "JMPF @6, L2 /* 3: */;\n" +
                "RAISE C2 /* 4: */;\n" +
                "RETURN /* 5: */;\n" +
                "REF O0, L1 /* 6: */;\n" +
                "RETURN /* 7: */;", program);
        }
    }
}


// End FlatFileFennelRel.java