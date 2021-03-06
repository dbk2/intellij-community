/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.debugger.settings;

import com.intellij.debugger.DebuggerBundle;
import com.intellij.debugger.engine.DebuggerUtils;
import com.intellij.debugger.engine.JVMNameUtil;
import com.intellij.debugger.engine.evaluation.CodeFragmentKind;
import com.intellij.debugger.engine.evaluation.TextWithImports;
import com.intellij.debugger.engine.evaluation.TextWithImportsImpl;
import com.intellij.debugger.impl.DebuggerUtilsEx;
import com.intellij.debugger.ui.DebuggerExpressionTextField;
import com.intellij.debugger.ui.JavaDebuggerSupport;
import com.intellij.debugger.ui.tree.render.*;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.UnnamedConfigurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.ui.*;
import com.intellij.ui.table.JBTable;
import com.intellij.util.Function;
import com.intellij.util.ui.AbstractTableCellEditor;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Eugene Zhuravlev
 *         Date: Feb 24, 2005
 */
public class CompoundRendererConfigurable implements UnnamedConfigurable {
  private CompoundReferenceRenderer myRenderer;
  private CompoundReferenceRenderer myOriginalRenderer;
  private Project myProject;
  private ClassNameEditorWithBrowseButton myClassNameField;
  private JRadioButton myRbDefaultLabel;
  private JRadioButton myRbExpressionLabel;
  private JRadioButton myRbDefaultChildrenRenderer;
  private JRadioButton myRbExpressionChildrenRenderer;
  private JRadioButton myRbListChildrenRenderer;
  private DebuggerExpressionTextField myLabelEditor;
  private DebuggerExpressionTextField myChildrenEditor;
  private DebuggerExpressionTextField myChildrenExpandedEditor;
  private DebuggerExpressionTextField myListChildrenEditor;
  private JComponent myChildrenListEditor;
  private JLabel myExpandedLabel;
  private JPanel myMainPanel;
  private JBTable myTable;
  @NonNls private static final String EMPTY_PANEL_ID = "EMPTY";
  @NonNls private static final String DATA_PANEL_ID = "DATA";
  private static final int NAME_TABLE_COLUMN = 0;
  private static final int EXPRESSION_TABLE_COLUMN = 1;

  public CompoundRendererConfigurable(@Nullable Project project) {
    myProject = project;
  }

  public void setRenderer(NodeRenderer renderer) {
    if (renderer instanceof CompoundReferenceRenderer) {
      myRenderer = (CompoundReferenceRenderer)renderer;
      myOriginalRenderer = (CompoundReferenceRenderer)renderer.clone();
    }
    else {
      myRenderer = myOriginalRenderer = null;
    }
    reset();
  }

  public CompoundReferenceRenderer getRenderer() {
    return myRenderer;
  }

  public JComponent createComponent() {
    if (myProject == null) {
      myProject = JavaDebuggerSupport.getContextProjectForEditorFieldsInDebuggerConfigurables();
    }
    final JPanel panel = new JPanel(new GridBagLayout());

    myRbDefaultLabel = new JRadioButton(DebuggerBundle.message("label.compound.renderer.configurable.use.default.renderer"));
    myRbExpressionLabel = new JRadioButton(DebuggerBundle.message("label.compound.renderer.configurable.use.expression"));
    final ButtonGroup labelButtonsGroup = new ButtonGroup();
    labelButtonsGroup.add(myRbDefaultLabel);
    labelButtonsGroup.add(myRbExpressionLabel);

    myRbDefaultChildrenRenderer = new JRadioButton(DebuggerBundle.message("label.compound.renderer.configurable.use.default.renderer"));
    myRbExpressionChildrenRenderer = new JRadioButton(DebuggerBundle.message("label.compound.renderer.configurable.use.expression"));
    myRbListChildrenRenderer = new JRadioButton(DebuggerBundle.message("label.compound.renderer.configurable.use.expression.list"));
    final ButtonGroup childrenButtonGroup = new ButtonGroup();
    childrenButtonGroup.add(myRbDefaultChildrenRenderer);
    childrenButtonGroup.add(myRbExpressionChildrenRenderer);
    childrenButtonGroup.add(myRbListChildrenRenderer);

    myLabelEditor = new DebuggerExpressionTextField(myProject, null, "ClassLabelExpression");
    myChildrenEditor = new DebuggerExpressionTextField(myProject, null, "ClassChildrenExpression");
    myChildrenExpandedEditor = new DebuggerExpressionTextField(myProject, null, "ClassChildrenExpression");
    myChildrenListEditor = createChildrenListEditor();

    final ItemListener updateListener = new ItemListener() {
      public void itemStateChanged(ItemEvent e) {
        updateEnabledState();
      }
    };
    myRbExpressionLabel.addItemListener(updateListener);
    myRbListChildrenRenderer.addItemListener(updateListener);
    myRbExpressionChildrenRenderer.addItemListener(updateListener);

    myClassNameField = new ClassNameEditorWithBrowseButton(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        PsiClass psiClass = DebuggerUtils.getInstance()
          .chooseClassDialog(DebuggerBundle.message("title.compound.renderer.configurable.choose.renderer.reference.type"), myProject);
        if (psiClass != null) {
          String qName = JVMNameUtil.getNonAnonymousClassName(psiClass);
          myClassNameField.setText(qName);
          updateContext(qName);
        }
      }
    }, myProject);
    final EditorTextField textField = myClassNameField.getEditorTextField();
    final FocusAdapter updateContextListener = new FocusAdapter() {
      public void focusLost(FocusEvent e) {
        updateContext(myClassNameField.getText());
      }
    };
    textField.addFocusListener(updateContextListener);
    Disposer.register(myClassNameField, new Disposable() {
      @Override
      public void dispose() {
        textField.removeFocusListener(updateContextListener);
      }
    });

    panel.add(new JLabel(DebuggerBundle.message("label.compound.renderer.configurable.apply.to")),
              new GridBagConstraints(0, GridBagConstraints.RELATIVE, 1, 1, 1.0, 0.0, GridBagConstraints.NORTHWEST, GridBagConstraints.NONE,
                                     new Insets(0, 0, 0, 0), 0, 0));
    panel.add(myClassNameField, new GridBagConstraints(0, GridBagConstraints.RELATIVE, 1, 1, 1.0, 0.0, GridBagConstraints.NORTHWEST,
                                                       GridBagConstraints.HORIZONTAL, new Insets(4, 0, 0, 0), 0, 0));

    panel.add(new JLabel(DebuggerBundle.message("label.compound.renderer.configurable.when.rendering")),
              new GridBagConstraints(0, GridBagConstraints.RELATIVE, 1, 1, 1.0, 0.0, GridBagConstraints.NORTHWEST, GridBagConstraints.NONE,
                                     new Insets(20, 0, 0, 0), 0, 0));
    panel.add(myRbDefaultLabel,
              new GridBagConstraints(0, GridBagConstraints.RELATIVE, 1, 1, 1.0, 0.0, GridBagConstraints.NORTHWEST, GridBagConstraints.NONE,
                                     new Insets(0, 10, 0, 0), 0, 0));
    panel.add(myRbExpressionLabel,
              new GridBagConstraints(0, GridBagConstraints.RELATIVE, 1, 1, 1.0, 0.0, GridBagConstraints.NORTHWEST, GridBagConstraints.NONE,
                                     new Insets(0, 10, 0, 0), 0, 0));
    panel.add(myLabelEditor, new GridBagConstraints(0, GridBagConstraints.RELATIVE, 1, 1, 1.0, 0.0, GridBagConstraints.NORTHWEST,
                                                    GridBagConstraints.HORIZONTAL, new Insets(0, 30, 0, 0), 0, 0));

    panel.add(new JLabel(DebuggerBundle.message("label.compound.renderer.configurable.when.expanding")),
              new GridBagConstraints(0, GridBagConstraints.RELATIVE, 1, 1, 1.0, 0.0, GridBagConstraints.NORTHWEST, GridBagConstraints.NONE,
                                     new Insets(20, 0, 0, 0), 0, 0));
    panel.add(myRbDefaultChildrenRenderer,
              new GridBagConstraints(0, GridBagConstraints.RELATIVE, 1, 1, 1.0, 0.0, GridBagConstraints.NORTHWEST, GridBagConstraints.NONE,
                                     new Insets(0, 10, 0, 0), 0, 0));
    panel.add(myRbExpressionChildrenRenderer,
              new GridBagConstraints(0, GridBagConstraints.RELATIVE, 1, 1, 1.0, 0.0, GridBagConstraints.NORTHWEST, GridBagConstraints.NONE,
                                     new Insets(0, 10, 0, 0), 0, 0));
    panel.add(myChildrenEditor, new GridBagConstraints(0, GridBagConstraints.RELATIVE, 1, 1, 1.0, 0.0, GridBagConstraints.NORTHWEST,
                                                       GridBagConstraints.HORIZONTAL, new Insets(0, 30, 0, 0), 0, 0));
    myExpandedLabel = new JLabel(DebuggerBundle.message("label.compound.renderer.configurable.test.can.expand"));
    panel.add(myExpandedLabel,
              new GridBagConstraints(0, GridBagConstraints.RELATIVE, 1, 1, 1.0, 0.0, GridBagConstraints.NORTHWEST, GridBagConstraints.NONE,
                                     new Insets(4, 30, 0, 0), 0, 0));
    panel.add(myChildrenExpandedEditor, new GridBagConstraints(0, GridBagConstraints.RELATIVE, 1, 1, 1.0, 0.0, GridBagConstraints.NORTHWEST,
                                                               GridBagConstraints.HORIZONTAL, new Insets(0, 30, 0, 0), 0, 0));
    panel.add(myRbListChildrenRenderer, new GridBagConstraints(0, GridBagConstraints.RELATIVE, 1, 1, 1.0, 0.0, GridBagConstraints.NORTHWEST,
                                                               GridBagConstraints.HORIZONTAL, new Insets(0, 10, 0, 0), 0, 0));
    panel.add(myChildrenListEditor,
              new GridBagConstraints(0, GridBagConstraints.RELATIVE, 1, 1, 1.0, 1.0, GridBagConstraints.NORTHWEST, GridBagConstraints.BOTH,
                                     new Insets(4, 30, 0, 0), 0, 0));

    myMainPanel = new JPanel(new CardLayout());
    myMainPanel.add(new JPanel(), EMPTY_PANEL_ID);
    myMainPanel.add(panel, DATA_PANEL_ID);
    return myMainPanel;
  }

  private void updateContext(final String qName) {
    ApplicationManager.getApplication().runReadAction(new Runnable() {
      public void run() {
        final Project project = myProject;
        final PsiClass psiClass = project != null ? DebuggerUtils.findClass(qName, project, GlobalSearchScope.allScope(project)) : null;
        myLabelEditor.setContext(psiClass);
        myChildrenEditor.setContext(psiClass);
        myChildrenExpandedEditor.setContext(psiClass);
        myListChildrenEditor.setContext(psiClass);

        PsiType type = DebuggerUtils.getType(qName, project);
        myLabelEditor.setThisType(type);
        myChildrenEditor.setThisType(type);
        myChildrenExpandedEditor.setThisType(type);
        myListChildrenEditor.setThisType(type);
      }
    });

    // Need to recreate fields documents with the new context
    ApplicationManager.getApplication().invokeLater(new Runnable() {
      @Override
      public void run() {
        myLabelEditor.setText(myLabelEditor.getText());
        myChildrenEditor.setText(myChildrenEditor.getText());
        myChildrenExpandedEditor.setText(myChildrenExpandedEditor.getText());
        myListChildrenEditor.setText(myListChildrenEditor.getText());
      }
    }, ModalityState.any(), myProject.getDisposed());
  }

  private void updateEnabledState() {
    myLabelEditor.setEnabled(myRbExpressionLabel.isSelected());

    final boolean isChildrenExpression = myRbExpressionChildrenRenderer.isSelected();
    myChildrenExpandedEditor.setEnabled(isChildrenExpression);
    myExpandedLabel.setEnabled(isChildrenExpression);
    myChildrenEditor.setEnabled(isChildrenExpression);
    myTable.setEnabled(myRbListChildrenRenderer.isSelected());
  }

  private JComponent createChildrenListEditor() {
    final MyTableModel tableModel = new MyTableModel();
    myTable = new JBTable(tableModel);
    myListChildrenEditor = new DebuggerExpressionTextField(myProject, null, "NamedChildrenConfigurable");

    final TableColumn exprColumn = myTable.getColumnModel().getColumn(EXPRESSION_TABLE_COLUMN);
    exprColumn.setCellEditor(new AbstractTableCellEditor() {
      public Object getCellEditorValue() {
        return myListChildrenEditor.getText();
      }

      public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
        myListChildrenEditor.setText((TextWithImports)value);
        return myListChildrenEditor;
      }
    });
    exprColumn.setCellRenderer(new DefaultTableCellRenderer() {
      public Component getTableCellRendererComponent(JTable table,
                                                     Object value,
                                                     boolean isSelected,
                                                     boolean hasFocus,
                                                     int row,
                                                     int column) {
        final TextWithImports textWithImports = (TextWithImports)value;
        final String text = (textWithImports != null) ? textWithImports.getText() : "";
        return super.getTableCellRendererComponent(table, text, isSelected, hasFocus, row, column);
      }
    });

    return ToolbarDecorator.createDecorator(myTable)
      .setAddAction(new AnActionButtonRunnable() {
        @Override
        public void run(AnActionButton button) {
          tableModel.addRow("", DebuggerUtils.getInstance().createExpressionWithImports(""));
        }
      }).setRemoveAction(new AnActionButtonRunnable() {
        @Override
        public void run(AnActionButton button) {
          int selectedRow = myTable.getSelectedRow();
          if (selectedRow >= 0 && selectedRow < myTable.getRowCount()) {
            getTableModel().removeRow(selectedRow);
          }
        }
      }).setMoveUpAction(new AnActionButtonRunnable() {
        @Override
        public void run(AnActionButton button) {
          TableUtil.moveSelectedItemsUp(myTable);
        }
      }).setMoveDownAction(new AnActionButtonRunnable() {
        @Override
        public void run(AnActionButton button) {
          TableUtil.moveSelectedItemsDown(myTable);
        }
      }).createPanel();
  }

  public boolean isModified() {
    if (myRenderer == null) {
      return false;
    }
    final CompoundReferenceRenderer cloned = (CompoundReferenceRenderer)myRenderer.clone();
    flushDataTo(cloned);
    return !DebuggerUtilsEx.externalizableEqual(cloned, myOriginalRenderer);
  }

  public void apply() throws ConfigurationException {
    if (myRenderer == null) {
      return;
    }
    flushDataTo(myRenderer);
    // update the renderer to compare with in order to find out whether we've been modified since last apply
    myOriginalRenderer = (CompoundReferenceRenderer)myRenderer.clone();
  }

  private void flushDataTo(final CompoundReferenceRenderer renderer) { // label
    LabelRenderer labelRenderer = null;
    if (myRbExpressionLabel.isSelected()) {
      labelRenderer = new LabelRenderer();
      labelRenderer.setLabelExpression(myLabelEditor.getText());
    }
    renderer.setLabelRenderer(labelRenderer);
    // children
    ChildrenRenderer childrenRenderer = null;
    if (myRbExpressionChildrenRenderer.isSelected()) {
      childrenRenderer = new ExpressionChildrenRenderer();
      ((ExpressionChildrenRenderer)childrenRenderer).setChildrenExpression(myChildrenEditor.getText());
      ((ExpressionChildrenRenderer)childrenRenderer).setChildrenExpandable(myChildrenExpandedEditor.getText());
    }
    else if (myRbListChildrenRenderer.isSelected()) {
      childrenRenderer = new EnumerationChildrenRenderer(getTableModel().getExpressions());
    }
    renderer.setChildrenRenderer(childrenRenderer);
    // classname
    renderer.setClassName(myClassNameField.getText());
  }

  public void reset() {
    final TextWithImports emptyExpressionFragment = new TextWithImportsImpl(CodeFragmentKind.EXPRESSION, "");
    ((CardLayout)myMainPanel.getLayout()).show(myMainPanel, myRenderer == null ? EMPTY_PANEL_ID : DATA_PANEL_ID);
    if (myRenderer == null) {
      return;
    }
    final String className = myRenderer.getClassName();
    myClassNameField.setText(className);
    final ValueLabelRenderer labelRenderer = myRenderer.getLabelRenderer();
    final ChildrenRenderer childrenRenderer = myRenderer.getChildrenRenderer();
    final NodeRendererSettings rendererSettings = NodeRendererSettings.getInstance();

    if (rendererSettings.isBase(labelRenderer)) {
      myRbDefaultLabel.setSelected(true);
      myLabelEditor.setText(emptyExpressionFragment);
    }
    else {
      myRbExpressionLabel.setSelected(true);
      myLabelEditor.setText(((LabelRenderer)labelRenderer).getLabelExpression());
    }

    if (rendererSettings.isBase(childrenRenderer)) {
      myRbDefaultChildrenRenderer.setSelected(true);
      myChildrenEditor.setText(emptyExpressionFragment);
      myChildrenExpandedEditor.setText(emptyExpressionFragment);
      getTableModel().clear();
    }
    else if (childrenRenderer instanceof ExpressionChildrenRenderer) {
      myRbExpressionChildrenRenderer.setSelected(true);
      final ExpressionChildrenRenderer exprRenderer = (ExpressionChildrenRenderer)childrenRenderer;
      myChildrenEditor.setText(exprRenderer.getChildrenExpression());
      myChildrenExpandedEditor.setText(exprRenderer.getChildrenExpandable());
      getTableModel().clear();
    }
    else {
      myRbListChildrenRenderer.setSelected(true);
      myChildrenEditor.setText(emptyExpressionFragment);
      myChildrenExpandedEditor.setText(emptyExpressionFragment);
      if (childrenRenderer instanceof EnumerationChildrenRenderer) {
        getTableModel().init(((EnumerationChildrenRenderer)childrenRenderer).getChildren());
      }
      else {
        getTableModel().clear();
      }
    }

    updateEnabledState();
    updateContext(className);
  }

  public void disposeUIResources() {
    myRenderer = null;
    myOriginalRenderer = null;
    myLabelEditor.dispose();
    myChildrenEditor.dispose();
    myChildrenExpandedEditor.dispose();
    myListChildrenEditor.dispose();
    Disposer.dispose(myClassNameField);
    myLabelEditor = null;
    myChildrenEditor = null;
    myChildrenExpandedEditor = null;
    myListChildrenEditor = null;
    myClassNameField = null;
    myProject = null;
  }

  private MyTableModel getTableModel() {
    return (MyTableModel)myTable.getModel();
  }

  private final class MyTableModel extends AbstractTableModel {
    private final List<Row> myData = new ArrayList<Row>();

    public MyTableModel() {
    }

    public void init(List<Pair<String, TextWithImports>> data) {
      myData.clear();
      for (final Pair<String, TextWithImports> pair : data) {
        myData.add(new Row(pair.getFirst(), pair.getSecond()));
      }
    }

    public int getColumnCount() {
      return 2;
    }

    public int getRowCount() {
      return myData.size();
    }

    public boolean isCellEditable(int rowIndex, int columnIndex) {
      return true;
    }

    public Class getColumnClass(int columnIndex) {
      switch (columnIndex) {
        case NAME_TABLE_COLUMN:
          return String.class;
        case EXPRESSION_TABLE_COLUMN:
          return TextWithImports.class;
        default:
          return super.getColumnClass(columnIndex);
      }
    }

    public Object getValueAt(int rowIndex, int columnIndex) {
      if (rowIndex >= getRowCount()) {
        return null;
      }
      final Row row = myData.get(rowIndex);
      switch (columnIndex) {
        case NAME_TABLE_COLUMN:
          return row.name;
        case EXPRESSION_TABLE_COLUMN:
          return row.value;
        default:
          return null;
      }
    }

    public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
      if (rowIndex >= getRowCount()) {
        return;
      }
      final Row row = myData.get(rowIndex);
      switch (columnIndex) {
        case NAME_TABLE_COLUMN:
          row.name = (String)aValue;
          break;
        case EXPRESSION_TABLE_COLUMN:
          row.value = (TextWithImports)aValue;
          break;
      }
    }

    public String getColumnName(int columnIndex) {
      switch (columnIndex) {
        case NAME_TABLE_COLUMN:
          return DebuggerBundle.message("label.compound.renderer.configurable.table.header.name");
        case EXPRESSION_TABLE_COLUMN:
          return DebuggerBundle.message("label.compound.renderer.configurable.table.header.expression");
        default:
          return "";
      }
    }

    public void addRow(final String name, final TextWithImports expressionWithImports) {
      myData.add(new Row(name, expressionWithImports));
      final int lastRow = myData.size() - 1;
      fireTableRowsInserted(lastRow, lastRow);
    }

    public void removeRow(final int row) {
      if (row >= 0 && row < myData.size()) {
        myData.remove(row);
        fireTableRowsDeleted(row, row);
      }
    }

    public void clear() {
      myData.clear();
      fireTableDataChanged();
    }

    public List<Pair<String, TextWithImports>> getExpressions() {
      final ArrayList<Pair<String, TextWithImports>> pairs = new ArrayList<Pair<String, TextWithImports>>(myData.size());
      for (final Row row : myData) {
        pairs.add(Pair.create(row.name, row.value));
      }
      return pairs;
    }

    private final class Row {
      public String name;
      public TextWithImports value;

      public Row(final String name, final TextWithImports value) {
        this.name = name;
        this.value = value;
      }
    }
  }
  
  private static class ClassNameEditorWithBrowseButton extends ReferenceEditorWithBrowseButton {
    private ClassNameEditorWithBrowseButton(ActionListener browseActionListener, final Project project) {
      super(browseActionListener, project,
            new Function<String, Document>() {
              @Override
              public Document fun(String s) {
                PsiPackage defaultPackage = JavaPsiFacade.getInstance(project).findPackage("");
                final JavaCodeFragment fragment =
                  JavaCodeFragmentFactory.getInstance(project).createReferenceCodeFragment(s, defaultPackage, true, true);
                fragment.setVisibilityChecker(JavaCodeFragment.VisibilityChecker.EVERYTHING_VISIBLE);
                return PsiDocumentManager.getInstance(project).getDocument(fragment);
              }
            }, "");
    }
  }
}
