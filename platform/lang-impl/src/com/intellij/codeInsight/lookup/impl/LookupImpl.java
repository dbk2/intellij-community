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

package com.intellij.codeInsight.lookup.impl;

import com.intellij.codeInsight.FileModificationService;
import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.completion.impl.CamelHumpMatcher;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInsight.hint.HintManager;
import com.intellij.codeInsight.hint.HintManagerImpl;
import com.intellij.codeInsight.lookup.*;
import com.intellij.featureStatistics.FeatureUsageTracker;
import com.intellij.ide.IdeEventQueue;
import com.intellij.ide.ui.UISettings;
import com.intellij.lang.LangBundle;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.editor.event.DocumentAdapter;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.DebugUtil;
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageUtil;
import com.intellij.ui.*;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBList;
import com.intellij.ui.popup.AbstractPopup;
import com.intellij.util.CollectConsumer;
import com.intellij.util.containers.ConcurrentHashMap;
import com.intellij.util.containers.ConcurrentWeakHashMap;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.update.Activatable;
import com.intellij.util.ui.update.UiNotifyConnector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LookupImpl extends LightweightHint implements LookupEx, Disposable, WeighingContext {
  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInsight.lookup.impl.LookupImpl");

  private final LookupOffsets myOffsets;
  private final Project myProject;
  private final Editor myEditor;
  private final JBList myList = new JBList(new CollectionListModel<LookupElement>()) {
    @Override
    protected void processKeyEvent(@NotNull final KeyEvent e) {
      final char keyChar = e.getKeyChar();
      if (keyChar == KeyEvent.VK_ENTER || keyChar == KeyEvent.VK_TAB) {
        IdeFocusManager.getInstance(myProject).requestFocus(myEditor.getContentComponent(), true).doWhenDone(new Runnable() {
          @Override
          public void run() {
            IdeEventQueue.getInstance().getKeyEventDispatcher().dispatchKeyEvent(e);
          }
        });
        return;
      }

      super.processKeyEvent(e);
    }

    ExpandableItemsHandler<Integer> myExtender = new CompletionExtender(this);
    @NotNull
    @Override
    public ExpandableItemsHandler<Integer> getExpandableItemsHandler() {
      return myExtender;
    }
  };
  final LookupCellRenderer myCellRenderer;

  private final List<LookupListener> myListeners = ContainerUtil.createLockFreeCopyOnWriteList();

  private long myStampShown = 0;
  private boolean myShown = false;
  private boolean myDisposed = false;
  private boolean myHidden = false;
  private boolean mySelectionTouched;
  private FocusDegree myFocusDegree = FocusDegree.FOCUSED;
  private volatile boolean myCalculating;
  private final Advertiser myAdComponent;
  volatile int myLookupTextWidth = 50;
  private boolean myChangeGuard;
  private volatile LookupArranger myArranger;
  private LookupArranger myPresentableArranger;
  private final Map<LookupElement, PrefixMatcher> myMatchers = new ConcurrentHashMap<LookupElement, PrefixMatcher>(
    ContainerUtil.<LookupElement>identityStrategy());
  private final Map<LookupElement, Font> myCustomFonts = new ConcurrentWeakHashMap<LookupElement, Font>(
    ContainerUtil.<LookupElement>identityStrategy());
  private boolean myStartCompletionWhenNothingMatches;
  boolean myResizePending;
  private boolean myFinishing;
  boolean myUpdating;
  private LookupUi myUi;

  public LookupImpl(Project project, Editor editor, @NotNull LookupArranger arranger) {
    super(new JPanel(new BorderLayout()));
    setForceShowAsPopup(true);
    setCancelOnClickOutside(false);
    setResizable(true);
    AbstractPopup.suppressMacCornerFor(getComponent());

    myProject = project;
    myEditor = editor;
    myArranger = arranger;
    myPresentableArranger = arranger;

    myCellRenderer = new LookupCellRenderer(this);
    myList.setCellRenderer(myCellRenderer);

    myList.setFocusable(false);
    myList.setFixedCellWidth(50);

    myList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    myList.setBackground(LookupCellRenderer.BACKGROUND_COLOR);

    myList.getExpandableItemsHandler();

    myAdComponent = new Advertiser();

    myOffsets = new LookupOffsets(editor);

    final CollectionListModel<LookupElement> model = getListModel();
    addEmptyItem(model);
    updateListHeight(model);

    addListeners();
  }

  private CollectionListModel<LookupElement> getListModel() {
    //noinspection unchecked
    return (CollectionListModel<LookupElement>)myList.getModel();
  }

  public void setArranger(LookupArranger arranger) {
    myArranger = arranger;
  }

  public FocusDegree getFocusDegree() {
    return myFocusDegree;
  }

  @Override
  public boolean isFocused() {
    return getFocusDegree() == FocusDegree.FOCUSED;
  }

  public void setFocusDegree(FocusDegree focusDegree) {
    myFocusDegree = focusDegree;
  }

  public boolean isCalculating() {
    return myCalculating;
  }

  public void setCalculating(final boolean calculating) {
    myCalculating = calculating;
    if (myUi != null) {
      myUi.setCalculating(calculating);
    }
  }

  public void markSelectionTouched() {
    if (!ApplicationManager.getApplication().isUnitTestMode()) {
      ApplicationManager.getApplication().assertIsDispatchThread();
    }
    mySelectionTouched = true;
    myList.repaint();
  }

  @TestOnly
  public void setSelectionTouched(boolean selectionTouched) {
    mySelectionTouched = selectionTouched;
  }

  public void resort(boolean addAgain) {
    final List<LookupElement> items = getItems();

    synchronized (myList) {
      myPresentableArranger.prefixChanged(this);
      getListModel().removeAll();
    }

    if (addAgain) {
      for (final LookupElement item : items) {
        addItem(item, itemMatcher(item));
      }
    }
    refreshUi(true, true);
  }

  public boolean addItem(LookupElement item, PrefixMatcher matcher) {
    LookupElementPresentation presentation = renderItemApproximately(item);
    if (containsDummyIdentifier(presentation.getItemText()) ||
        containsDummyIdentifier(presentation.getTailText()) ||
        containsDummyIdentifier(presentation.getTypeText())) {
      return false;
    }

    myMatchers.put(item, matcher);
    updateLookupWidth(item, presentation);
    synchronized (myList) {
      myArranger.addElement(this, item, presentation);
    }
    return true;
  }

  private static boolean containsDummyIdentifier(@Nullable final String s) {
    return s != null && s.contains(CompletionUtil.DUMMY_IDENTIFIER_TRIMMED);
  }

  public void updateLookupWidth(LookupElement item) {
    updateLookupWidth(item, renderItemApproximately(item));
  }

  private void updateLookupWidth(LookupElement item, LookupElementPresentation presentation) {
    final Font customFont = myCellRenderer.getFontAbleToDisplay(presentation);
    if (customFont != null) {
      myCustomFonts.put(item, customFont);
    }
    int maxWidth = myCellRenderer.updateMaximumWidth(presentation, item);
    myLookupTextWidth = Math.max(maxWidth, myLookupTextWidth);
  }

  @Nullable
  public Font getCustomFont(LookupElement item, boolean bold) {
    Font font = myCustomFonts.get(item);
    return font == null ? null : bold ? font.deriveFont(Font.BOLD) : font;
  }

  public void requestResize() {
    ApplicationManager.getApplication().assertIsDispatchThread();
    myResizePending = true;
  }

  public Collection<LookupElementAction> getActionsFor(LookupElement element) {
    final CollectConsumer<LookupElementAction> consumer = new CollectConsumer<LookupElementAction>();
    for (LookupActionProvider provider : LookupActionProvider.EP_NAME.getExtensions()) {
      provider.fillActions(element, this, consumer);
    }
    if (!consumer.getResult().isEmpty()) {
      consumer.consume(new ShowHideIntentionIconLookupAction());
    }
    return consumer.getResult();
  }

  public JList getList() {
    return myList;
  }

  @Override
  public List<LookupElement> getItems() {
    synchronized (myList) {
      return ContainerUtil.findAll(getListModel().toList(), new Condition<LookupElement>() {
        @Override
        public boolean value(LookupElement element) {
          return !(element instanceof EmptyLookupItem);
        }
      });
    }
  }

  public String getAdditionalPrefix() {
    return myOffsets.getAdditionalPrefix();
  }

  void appendPrefix(char c) {
    checkValid();
    myOffsets.appendPrefix(c);
    synchronized (myList) {
      myPresentableArranger.prefixChanged(this);
    }
    requestResize();
    refreshUi(false, true);
    ensureSelectionVisible(true);
  }

  public void setStartCompletionWhenNothingMatches(boolean startCompletionWhenNothingMatches) {
    myStartCompletionWhenNothingMatches = startCompletionWhenNothingMatches;
  }

  public boolean isStartCompletionWhenNothingMatches() {
    return myStartCompletionWhenNothingMatches;
  }

  public void ensureSelectionVisible(boolean forceTopSelection) {
    if (isSelectionVisible() && !forceTopSelection) {
      return;
    }

    if (!forceTopSelection) {
      ListScrollingUtil.ensureIndexIsVisible(myList, myList.getSelectedIndex(), 1);
      return;
    }

    // selected item should be at the top of the visible list
    int top = myList.getSelectedIndex();
    if (top > 0) {
      top--; // show one element above the selected one to give the hint that there are more available via scrolling
    }

    int firstVisibleIndex = myList.getFirstVisibleIndex();
    if (firstVisibleIndex == top) {
      return;
    }

    ListScrollingUtil.ensureRangeIsVisible(myList, top, top + myList.getLastVisibleIndex() - firstVisibleIndex);
  }

  boolean truncatePrefix(boolean preserveSelection) {
    if (!myOffsets.truncatePrefix()) {
      return false;
    }

    if (preserveSelection) {
      markSelectionTouched();
    }

    boolean shouldUpdate;
    synchronized (myList) {
      shouldUpdate = myPresentableArranger == myArranger;
      myPresentableArranger.prefixChanged(this);
    }
    requestResize();
    if (shouldUpdate) {
      refreshUi(false, true);
      ensureSelectionVisible(true);
    }

    return true;
  }

  private boolean updateList(boolean onExplicitAction, boolean reused) {
    if (!ApplicationManager.getApplication().isUnitTestMode()) {
      ApplicationManager.getApplication().assertIsDispatchThread();
    }
    checkValid();

    CollectionListModel<LookupElement> listModel = getListModel();
    synchronized (myList) {
      Pair<List<LookupElement>, Integer> pair = myPresentableArranger.arrangeItems(this, onExplicitAction || reused);
      List<LookupElement> items = pair.first;
      Integer toSelect = pair.second;
      if (toSelect == null || toSelect < 0 || items.size() > 0 && toSelect >= items.size()) {
        LOG.error("Arranger " + myPresentableArranger + " returned invalid selection index=" + toSelect + "; items=" + items);
      }

      myOffsets.checkMinPrefixLengthChanges(items, this);
      List<LookupElement> oldModel = listModel.toList();

      listModel.removeAll();
      if (!items.isEmpty()) {
        listModel.add(items);
      }
      else {
        addEmptyItem(listModel);
      }

      updateListHeight(listModel);

      myList.setSelectedIndex(toSelect);
      return !ContainerUtil.equalsIdentity(oldModel, items);
    }

  }

  private boolean isSelectionVisible() {
    return ListScrollingUtil.isIndexFullyVisible(myList, myList.getSelectedIndex());
  }

  private boolean checkReused() {
    synchronized (myList) {
      if (myPresentableArranger != myArranger) {
        myPresentableArranger = myArranger;
        myOffsets.clearAdditionalPrefix();
        myPresentableArranger.prefixChanged(this);
        return true;
      }
      return false;
    }
  }

  private void updateListHeight(ListModel model) {
    myList.setFixedCellHeight(myCellRenderer.getListCellRendererComponent(myList, model.getElementAt(0), 0, false, false).getPreferredSize().height);

    myList.setVisibleRowCount(Math.min(model.getSize(), UISettings.getInstance().MAX_LOOKUP_LIST_HEIGHT));
  }

  private void addEmptyItem(CollectionListModel<LookupElement> model) {
    LookupItem<String> item = new EmptyLookupItem(myCalculating ? " " : LangBundle.message("completion.no.suggestions"), false);
    myMatchers.put(item, new CamelHumpMatcher(""));
    model.add(item);

    updateLookupWidth(item);
    requestResize();
  }

  private static LookupElementPresentation renderItemApproximately(LookupElement item) {
    final LookupElementPresentation p = new LookupElementPresentation();
    item.renderElement(p);
    return p;
  }

  @NotNull
  @Override
  public String itemPattern(@NotNull LookupElement element) {
    String prefix = itemMatcher(element).getPrefix();
    String additionalPrefix = getAdditionalPrefix();
    return additionalPrefix.isEmpty() ? prefix : prefix + additionalPrefix;
  }

  @Override
  @NotNull
  public PrefixMatcher itemMatcher(@NotNull LookupElement item) {
    PrefixMatcher matcher = itemMatcherNullable(item);
    if (matcher == null) {
      throw new AssertionError("Item not in lookup: item=" + item + "; lookup items=" + getItems());
    }
    return matcher;
  }

  public PrefixMatcher itemMatcherNullable(LookupElement item) {
    return myMatchers.get(item);
  }

  public void finishLookup(final char completionChar) {
    finishLookup(completionChar, (LookupElement)myList.getSelectedValue());
  }

  public void finishLookup(char completionChar, @Nullable final LookupElement item) {
    //noinspection deprecation,unchecked
    if (item == null ||
        item instanceof EmptyLookupItem ||
        item.getObject() instanceof DeferredUserLookupValue &&
        item.as(LookupItem.CLASS_CONDITION_KEY) != null &&
        !((DeferredUserLookupValue)item.getObject()).handleUserSelection(item.as(LookupItem.CLASS_CONDITION_KEY), myProject)) {
      doHide(false, true);
      fireItemSelected(null, completionChar);
      return;
    }

    if (myDisposed) { // DeferredUserLookupValue could close us in any way
      return;
    }

    final PsiFile file = getPsiFile();
    boolean writableOk = file == null || FileModificationService.getInstance().prepareFileForWrite(file);
    if (myDisposed) { // ensureFilesWritable could close us by showing a dialog
      return;
    }

    if (!writableOk) {
      doHide(false, true);
      fireItemSelected(null, completionChar);
      return;
    }

    final String prefix = itemPattern(item);
    boolean plainMatch = ContainerUtil.or(item.getAllLookupStrings(), new Condition<String>() {
      @Override
      public boolean value(String s) {
        return StringUtil.containsIgnoreCase(s, prefix);
      }
    });
    if (!plainMatch) {
      FeatureUsageTracker.getInstance().triggerFeatureUsed(CodeCompletionFeatures.EDITING_COMPLETION_CAMEL_HUMPS);
    }

    myFinishing = true;
    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      public void run() {
        myEditor.getDocument().startGuardedBlockChecking();
        try {
          insertLookupString(item, getPrefixLength(item));
        }
        finally {
          myEditor.getDocument().stopGuardedBlockChecking();
        }
      }
    });

    if (myDisposed) { // any document listeners could close us
      return;
    }

    doHide(false, true);

    fireItemSelected(item, completionChar);
  }

  public int getPrefixLength(LookupElement item) {
    return myOffsets.getPrefixLength(item, this);
  }

  private void insertLookupString(LookupElement item, final int prefix) {
    final Document document = myEditor.getDocument();

    final String lookupString = getCaseCorrectedLookupString(item);

    if (myEditor.getSelectionModel().hasBlockSelection()) {
      LogicalPosition blockStart = myEditor.getSelectionModel().getBlockStart();
      LogicalPosition blockEnd = myEditor.getSelectionModel().getBlockEnd();
      assert blockStart != null && blockEnd != null;

      int minLine = Math.min(blockStart.line, blockEnd.line);
      int maxLine = Math.max(blockStart.line, blockEnd.line);
      int minColumn = Math.min(blockStart.column, blockEnd.column);
      int maxColumn = Math.max(blockStart.column, blockEnd.column);

      int caretLine = document.getLineNumber(myEditor.getCaretModel().getOffset());

      for (int line = minLine; line <= maxLine; line++) {
        int bs = myEditor.logicalPositionToOffset(new LogicalPosition(line, minColumn));
        int start = bs - prefix;
        int end = myEditor.logicalPositionToOffset(new LogicalPosition(line, maxColumn));
        if (start > end) {
          LOG.error("bs=" + bs + "; start=" + start + "; end=" + end +
                    "; blockStart=" + blockStart + "; blockEnd=" + blockEnd + "; line=" + line + "; len=" +
                    (document.getLineEndOffset(line) - document.getLineStartOffset(line)));
        }
        document.replaceString(start, end, lookupString);
      }
      LogicalPosition start = new LogicalPosition(minLine, minColumn - prefix);
      LogicalPosition end = new LogicalPosition(maxLine, start.column + lookupString.length());
      myEditor.getSelectionModel().setBlockSelection(start, end);
      myEditor.getCaretModel().moveToLogicalPosition(new LogicalPosition(caretLine, end.column));
    } else {
      final Editor hostEditor = InjectedLanguageUtil.getTopLevelEditor(myEditor);
      hostEditor.getCaretModel().runForEachCaret(new CaretAction() {
        @Override
        public void perform(Caret caret) {
          EditorModificationUtil.deleteSelectedText(hostEditor);
          final int caretOffset = hostEditor.getCaretModel().getOffset();
          int lookupStart = caretOffset - prefix;

          int len = hostEditor.getDocument().getTextLength();
          LOG.assertTrue(lookupStart >= 0 && lookupStart <= len,
                         "ls: " + lookupStart + " caret: " + caretOffset + " prefix:" + prefix + " doc: " + len);
          LOG.assertTrue(caretOffset >= 0 && caretOffset <= len, "co: " + caretOffset + " doc: " + len);

          hostEditor.getDocument().replaceString(lookupStart, caretOffset, lookupString);

          int offset = lookupStart + lookupString.length();
          hostEditor.getCaretModel().moveToOffset(offset);
          hostEditor.getSelectionModel().removeSelection();
        }
      });
    }

    myEditor.getScrollingModel().scrollToCaret(ScrollType.RELATIVE);
  }

  private String getCaseCorrectedLookupString(LookupElement item) {
    String lookupString = item.getLookupString();
    if (item.isCaseSensitive()) {
      return lookupString;
    }

    final String prefix = itemPattern(item);
    final int length = prefix.length();
    if (length == 0 || !itemMatcher(item).prefixMatches(prefix)) return lookupString;
    boolean isAllLower = true;
    boolean isAllUpper = true;
    boolean sameCase = true;
    for (int i = 0; i < length && (isAllLower || isAllUpper || sameCase); i++) {
      final char c = prefix.charAt(i);
      boolean isLower = Character.isLowerCase(c);
      boolean isUpper = Character.isUpperCase(c);
      // do not take this kind of symbols into account ('_', '@', etc.)
      if (!isLower && !isUpper) continue;
      isAllLower = isAllLower && isLower;
      isAllUpper = isAllUpper && isUpper;
      sameCase = sameCase && isLower == Character.isLowerCase(lookupString.charAt(i));
    }
    if (sameCase) return lookupString;
    if (isAllLower) return lookupString.toLowerCase();
    if (isAllUpper) return StringUtil.toUpperCase(lookupString);
    return lookupString;
  }

  @Override
  public int getLookupStart() {
    return myOffsets.getLookupStart(disposeTrace);
  }

  public int getLookupOriginalStart() {
    return myOffsets.getLookupOriginalStart();
  }

  public boolean performGuardedChange(Runnable change) {
    return performGuardedChange(change, null);
  }

  public boolean performGuardedChange(Runnable change, @Nullable final String debug) {
    checkValid();
    assert !myChangeGuard : "already in change";

    myEditor.getDocument().startGuardedBlockChecking();
    myChangeGuard = true;
    boolean result;
    try {
      result = myOffsets.performGuardedChange(change, debug);
    }
    finally {
      myEditor.getDocument().stopGuardedBlockChecking();
      myChangeGuard = false;
    }
    if (!result || myDisposed) {
      hide();
      return false;
    }
    if (isVisible()) {
      HintManagerImpl.updateLocation(this, myEditor, myUi.calculatePosition().getLocation());
    }
    checkValid();
    return true;
  }

  @Override
  public boolean vetoesHiding() {
    return myChangeGuard;
  }

  public boolean isAvailableToUser() {
    if (ApplicationManager.getApplication().isUnitTestMode()) {
      return myShown;
    }
    return isVisible();
  }

  public boolean isShown() {
    if (!ApplicationManager.getApplication().isUnitTestMode()) {
      ApplicationManager.getApplication().assertIsDispatchThread();
    }
    return myShown;
  }

  public boolean showLookup() {
    ApplicationManager.getApplication().assertIsDispatchThread();
    checkValid();
    LOG.assertTrue(!myShown);
    myShown = true;
    myStampShown = System.currentTimeMillis();

    if (ApplicationManager.getApplication().isUnitTestMode()) return true;

    if (!myEditor.getContentComponent().isShowing()) {
      hide();
      return false;
    }

    myAdComponent.showRandomText();

    myUi = new LookupUi(this, myAdComponent, myList, myProject);
    myUi.setCalculating(myCalculating);
    Point p = myUi.calculatePosition().getLocation();
    HintManagerImpl.getInstanceImpl().showEditorHint(this, myEditor, p, HintManager.HIDE_BY_ESCAPE | HintManager.UPDATE_BY_SCROLLING, 0, false,
                                                     HintManagerImpl.createHintHint(myEditor, p, this, HintManager.UNDER).setAwtTooltip(false));

    if (!isVisible()) {
      hide();
      return false;
    }

    DaemonCodeAnalyzer.getInstance(myProject).disableUpdateByTimer(this);

    LOG.assertTrue(myList.isShowing(), "!showing, disposed=" + myDisposed);

    return true;
  }

  public Advertiser getAdvertiser() {
    return myAdComponent;
  }

  public boolean mayBeNoticed() {
    return myStampShown > 0 && System.currentTimeMillis() - myStampShown > 300;
  }

  private void addListeners() {
    myEditor.getDocument().addDocumentListener(new DocumentAdapter() {
      @Override
      public void documentChanged(DocumentEvent e) {
        if (!myChangeGuard && !myFinishing) {
          hide();
        }
      }
    }, this);

    final CaretListener caretListener = new CaretAdapter() {
      @Override
      public void caretPositionChanged(CaretEvent e) {
        if (!myChangeGuard && !myFinishing) {
          hide();
        }
      }
    };
    final SelectionListener selectionListener = new SelectionListener() {
      @Override
      public void selectionChanged(final SelectionEvent e) {
        if (!myChangeGuard && !myFinishing) {
          hide();
        }
      }
    };
    final EditorMouseListener mouseListener = new EditorMouseAdapter() {
      @Override
      public void mouseClicked(EditorMouseEvent e){
        e.consume();
        hide();
      }
    };

    myEditor.getCaretModel().addCaretListener(caretListener);
    myEditor.getSelectionModel().addSelectionListener(selectionListener);
    myEditor.addEditorMouseListener(mouseListener);
    Disposer.register(this, new Disposable() {
      @Override
      public void dispose() {
        myEditor.getCaretModel().removeCaretListener(caretListener);
        myEditor.getSelectionModel().removeSelectionListener(selectionListener);
        myEditor.removeEditorMouseListener(mouseListener);
      }
    });

    JComponent editorComponent = myEditor.getContentComponent();
    if (editorComponent.isShowing()) {
      Disposer.register(this, new UiNotifyConnector(editorComponent, new Activatable() {
        @Override
        public void showNotify() {
        }
  
        @Override
        public void hideNotify() {
          hideLookup(false);
        }
      }));
    }

    myList.addListSelectionListener(new ListSelectionListener() {
      private LookupElement oldItem = null;

      @Override
      public void valueChanged(@NotNull ListSelectionEvent e){
        final LookupElement item = getCurrentItem();
        if (oldItem != item && !myList.isEmpty()) { // do not update on temporary model wipe
          fireCurrentItemChanged(item);
          if (myDisposed) { //a listener may have decided to close us, what can we do?
            return;
          }
          oldItem = item;
        }
      }
    });

    new ClickListener() {
      @Override
      public boolean onClick(@NotNull MouseEvent e, int clickCount) {
        setFocusDegree(FocusDegree.FOCUSED);
        markSelectionTouched();

        if (clickCount == 2){
          CommandProcessor.getInstance().executeCommand(myProject, new Runnable() {
            @Override
            public void run() {
              finishLookup(NORMAL_SELECT_CHAR);
            }
          }, "", null);
        }
        return true;
      }
    }.installOn(myList);
  }

  @Override
  @Nullable
  public LookupElement getCurrentItem(){
    LookupElement item = (LookupElement)myList.getSelectedValue();
    return item instanceof EmptyLookupItem ? null : item;
  }

  @Override
  public void setCurrentItem(LookupElement item){
    markSelectionTouched();
    myList.setSelectedValue(item, false);
  }

  @Override
  public void addLookupListener(LookupListener listener){
    myListeners.add(listener);
  }

  @Override
  public void removeLookupListener(LookupListener listener){
    myListeners.remove(listener);
  }

  @Override
  public Rectangle getCurrentItemBounds(){
    int index = myList.getSelectedIndex();
    if (index < 0) {
      LOG.error("No selected element, size=" + getListModel().getSize() + "; items" + getItems());
    }
    Rectangle itmBounds = myList.getCellBounds(index, index);
    if (itmBounds == null){
      LOG.error("No bounds for " + index + "; size=" + getListModel().getSize());
      return null;
    }
    Point layeredPanePoint=SwingUtilities.convertPoint(myList,itmBounds.x,itmBounds.y,getComponent());
    itmBounds.x = layeredPanePoint.x;
    itmBounds.y = layeredPanePoint.y;
    return itmBounds;
  }

  public void fireItemSelected(@Nullable final LookupElement item, char completionChar){
    PsiDocumentManager.getInstance(myProject).commitAllDocuments();

    if (!myListeners.isEmpty()){
      LookupEvent event = new LookupEvent(this, item, completionChar);
      for (LookupListener listener : myListeners) {
        try {
          listener.itemSelected(event);
        }
        catch (Throwable e) {
          LOG.error(e);
        }
      }
    }
  }

  private void fireLookupCanceled(final boolean explicitly) {
    if (!myListeners.isEmpty()){
      LookupEvent event = new LookupEvent(this, explicitly);
      for (LookupListener listener : myListeners) {
        try {
          listener.lookupCanceled(event);
        }
        catch (Throwable e) {
          LOG.error(e);
        }
      }
    }
  }

  void fireCurrentItemChanged(LookupElement item){
    if (!myListeners.isEmpty()){
      LookupEvent event = new LookupEvent(this, item, (char)0);
      for (LookupListener listener : myListeners) {
        listener.currentItemChanged(event);
      }
    }
  }

  public boolean fillInCommonPrefix(boolean explicitlyInvoked) {
    if (explicitlyInvoked) {
      setFocusDegree(FocusDegree.FOCUSED);
    }

    if (explicitlyInvoked && myCalculating) return false;
    if (!explicitlyInvoked && mySelectionTouched) return false;

    ListModel listModel = getListModel();
    if (listModel.getSize() <= 1) return false;

    if (listModel.getSize() == 0) return false;

    final LookupElement firstItem = (LookupElement)listModel.getElementAt(0);
    if (listModel.getSize() == 1 && firstItem instanceof EmptyLookupItem) return false;

    final PrefixMatcher firstItemMatcher = itemMatcher(firstItem);
    final String oldPrefix = firstItemMatcher.getPrefix();
    final String presentPrefix = oldPrefix + getAdditionalPrefix();
    String commonPrefix = getCaseCorrectedLookupString(firstItem);

    for (int i = 1; i < listModel.getSize(); i++) {
      LookupElement item = (LookupElement)listModel.getElementAt(i);
      if (item instanceof EmptyLookupItem) return false;
      if (!oldPrefix.equals(itemMatcher(item).getPrefix())) return false;

      final String lookupString = getCaseCorrectedLookupString(item);
      final int length = Math.min(commonPrefix.length(), lookupString.length());
      if (length < commonPrefix.length()) {
        commonPrefix = commonPrefix.substring(0, length);
      }

      for (int j = 0; j < length; j++) {
        if (commonPrefix.charAt(j) != lookupString.charAt(j)) {
          commonPrefix = lookupString.substring(0, j);
          break;
        }
      }

      if (commonPrefix.length() == 0 || commonPrefix.length() < presentPrefix.length()) {
        return false;
      }
    }

    if (commonPrefix.equals(presentPrefix)) {
      return false;
    }

    for (int i = 0; i < listModel.getSize(); i++) {
      LookupElement item = (LookupElement)listModel.getElementAt(i);
      if (!itemMatcher(item).cloneWithPrefix(commonPrefix).prefixMatches(item)) {
        return false;
      }
    }

    myOffsets.setInitialPrefix(presentPrefix, explicitlyInvoked);

    replacePrefix(presentPrefix, commonPrefix);
    return true;
  }

  public void replacePrefix(final String presentPrefix, final String newPrefix) {
    if (!performGuardedChange(new Runnable() {
      @Override
      public void run() {
        EditorModificationUtil.deleteSelectedText(myEditor);
        int offset = myEditor.getCaretModel().getOffset();
        final int start = offset - presentPrefix.length();
        myEditor.getDocument().replaceString(start, offset, newPrefix);

        Map<LookupElement, PrefixMatcher> newMatchers = new HashMap<LookupElement, PrefixMatcher>();
        for (LookupElement item : getItems()) {
          if (item.isValid()) {
            PrefixMatcher matcher = itemMatcher(item).cloneWithPrefix(newPrefix);
            if (matcher.prefixMatches(item)) {
              newMatchers.put(item, matcher);
            }
          }
        }
        myMatchers.clear();
        myMatchers.putAll(newMatchers);

        myOffsets.clearAdditionalPrefix();

        myEditor.getCaretModel().moveToOffset(start + newPrefix.length());
      }
    })) {
      return;
    }
    synchronized (myList) {
      myPresentableArranger.prefixChanged(this);
    }
    refreshUi(true, true);
  }

  @Override
  @Nullable
  public PsiFile getPsiFile() {
    return PsiDocumentManager.getInstance(myProject).getPsiFile(myEditor.getDocument());
  }

  @Override
  public boolean isCompletion() {
    return myArranger instanceof CompletionLookupArranger;
  }

  @Override
  public PsiElement getPsiElement() {
    PsiFile file = getPsiFile();
    if (file == null) return null;

    int offset = getLookupStart();
    if (offset > 0) return file.findElementAt(offset - 1);

    return file.findElementAt(0);
  }

  @Override
  public Editor getEditor() {
    return myEditor;
  }

  @Override
  public boolean isPositionedAboveCaret(){
    return myUi != null && myUi.isPositionedAboveCaret();
  }

  @Override
  public boolean isSelectionTouched() {
    return mySelectionTouched;
  }

  @Override
  public List<String> getAdvertisements() {
    return myAdComponent.getAdvertisements();
  }

  @Override
  public void hide(){
    hideLookup(true);
  }

  public void hideLookup(boolean explicitly) {
    ApplicationManager.getApplication().assertIsDispatchThread();

    if (myHidden) return;

    doHide(true, explicitly);
  }

  private void doHide(final boolean fireCanceled, final boolean explicitly) {
    if (myDisposed) {
      LOG.error(disposeTrace);
    }
    else {
      myHidden = true;

      try {
        super.hide();

        Disposer.dispose(this);

        assert myDisposed;
      }
      catch (Throwable e) {
        LOG.error(e);
      }
    }

    if (fireCanceled) {
      fireLookupCanceled(explicitly);
    }
  }

  public void restorePrefix() {
    myOffsets.restorePrefix(getLookupStart());
  }

  private static String staticDisposeTrace = null;
  private String disposeTrace = null;

  public static String getLastLookupDisposeTrace() {
    return staticDisposeTrace;
  }

  @Override
  public void dispose() {
    assert ApplicationManager.getApplication().isDispatchThread();
    assert myHidden;
    if (myDisposed) {
      LOG.error(disposeTrace);
      return;
    }

    myOffsets.disposeMarkers();
    myDisposed = true;
    disposeTrace = DebugUtil.currentStackTrace() + "\n============";
    //noinspection AssignmentToStaticFieldFromInstanceMethod
    staticDisposeTrace = disposeTrace;
  }

  public void refreshUi(boolean mayCheckReused, boolean onExplicitAction) {
    assert !myUpdating;
    myUpdating = true;
    try {
      final boolean reused = mayCheckReused && checkReused();
      boolean selectionVisible = isSelectionVisible();
      boolean itemsChanged = updateList(onExplicitAction, reused);
      if (isVisible()) {
        LOG.assertTrue(!ApplicationManager.getApplication().isUnitTestMode());
        myUi.refreshUi(selectionVisible, itemsChanged, reused, onExplicitAction);
      }
    }
    finally {
      myUpdating = false;
    }
  }

  public void markReused() {
    synchronized (myList) {
      myArranger = myArranger.createEmptyCopy();
    }
    requestResize();
  }

  public void addAdvertisement(@NotNull final String text, final @Nullable Color bgColor) {
    if (containsDummyIdentifier(text)) {
      return;
    }

    myAdComponent.addAdvertisement(text, bgColor);
    requestResize();
  }

  public boolean isLookupDisposed() {
    return myDisposed;
  }

  public void checkValid() {
    if (myDisposed) {
      throw new AssertionError("Disposed at: " + disposeTrace);
    }
  }

  @Override
  public void showItemPopup(JBPopup hint) {
    final Rectangle bounds = getCurrentItemBounds();
    hint.show(new RelativePoint(getComponent(), new Point(bounds.x + bounds.width, bounds.y)));
  }

  @Override
  public boolean showElementActions() {
    if (!isVisible()) return false;

    final LookupElement element = getCurrentItem();
    if (element == null) {
      return false;
    }

    final Collection<LookupElementAction> actions = getActionsFor(element);
    if (actions.isEmpty()) {
      return false;
    }

    showItemPopup(JBPopupFactory.getInstance().createListPopup(new LookupActionsStep(actions, this, element)));
    return true;
  }

  public Map<LookupElement,StringBuilder> getRelevanceStrings() {
    synchronized (myList) {
      return myPresentableArranger.getRelevanceStrings();
    }
  }

  public enum FocusDegree { FOCUSED, SEMI_FOCUSED, UNFOCUSED }

}
