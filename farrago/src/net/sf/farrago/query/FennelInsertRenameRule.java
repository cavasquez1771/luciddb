/*
// $Id$
// Farrago is an extensible data management system.
// Copyright (C) 2005-2007 The Eigenbase Project
// Copyright (C) 2003-2007 Disruptive Tech
// Copyright (C) 2005-2007 LucidEra, Inc.
// Portions Copyright (C) 2003-2007 John V. Sichi
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
package net.sf.farrago.query;

import org.eigenbase.rel.*;
import org.eigenbase.relopt.*;


/**
 * FennelInsertRenameRule is a rule for converting a rename-only Project
 * underneath an insert TableModificationRel into FennelRename.
 *
 * @author Zelaine Fong
 * @version $Id$
 */
public class FennelInsertRenameRule
    extends FennelRenameRule
{
    //~ Constructors -----------------------------------------------------------

    /**
     * Creates a new FennelInsertRenameRule object.
     */
    public FennelInsertRenameRule()
    {
        super(
            new RelOptRuleOperand(
                TableModificationRel.class,
                new RelOptRuleOperand[] {
                    new RelOptRuleOperand(ProjectRel.class, null)
                }));
    }

    //~ Methods ----------------------------------------------------------------

    // implement RelOptRule
    public void onMatch(RelOptRuleCall call)
    {
        TableModificationRel origTableMod = (TableModificationRel) call.rels[0];
        if (origTableMod.getOperation()
            != TableModificationRel.Operation.INSERT)
        {
            return;
        }

        ProjectRel project = (ProjectRel) call.rels[1];
        FennelRenameRel rename = renameChild(project);
        if (rename == null) {
            return;
        }

        TableModificationRel tableMod =
            new TableModificationRel(
                origTableMod.getCluster(),
                origTableMod.getTable(),
                origTableMod.getConnection(),
                rename,
                origTableMod.getOperation(),
                origTableMod.getUpdateColumnList(),
                origTableMod.isFlattened());

        call.transformTo(tableMod);
    }
}

// End FennelInsertRenameRule.java