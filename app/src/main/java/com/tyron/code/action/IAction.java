package com.tyron.code.action;

import com.tyron.code.completion.CompileTask;
import com.tyron.code.model.CodeActionList;

import org.openjdk.source.util.TreePath;

public abstract class IAction {

    /**
     * Determines whether the subclass supports this particular position
     * @param tree The current tree position
     * @param task current compile task
     * @return true if this action supports the current tree
     */
    public abstract boolean isApplicable(TreePath tree, CompileTask task);

    /**
     * Used to get the {@link CodeActionList} for this action
     * @param task The current CompileTask for the file
     * @return The CodeActionList, may have one or more actions
     */
    public abstract CodeActionList get(CompileTask task);
}