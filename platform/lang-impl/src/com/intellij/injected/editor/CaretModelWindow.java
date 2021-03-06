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

package com.intellij.injected.editor;

import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.util.Segment;
import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * @author Alexey
 */
public class CaretModelWindow implements CaretModel {
  private final CaretModel myDelegate;
  private final EditorEx myHostEditor;
  private final EditorWindow myEditorWindow;

  public CaretModelWindow(CaretModel delegate, EditorWindow editorWindow) {
    myDelegate = delegate;
    myHostEditor = (EditorEx)editorWindow.getDelegate();
    myEditorWindow = editorWindow;
  }

  @Override
  public void moveCaretRelatively(final int columnShift,
                                  final int lineShift,
                                  final boolean withSelection,
                                  final boolean blockSelection,
                                  final boolean scrollToCaret) {
    myDelegate.moveCaretRelatively(columnShift, lineShift, withSelection, blockSelection, scrollToCaret);
  }

  @Override
  public void moveToLogicalPosition(@NotNull final LogicalPosition pos) {
    LogicalPosition hostPos = myEditorWindow.injectedToHost(pos);
    myDelegate.moveToLogicalPosition(hostPos);
  }

  @Override
  public void moveToVisualPosition(@NotNull final VisualPosition pos) {
    LogicalPosition hostPos = myEditorWindow.injectedToHost(myEditorWindow.visualToLogicalPosition(pos));
    myDelegate.moveToLogicalPosition(hostPos);
  }

  @Override
  public void moveToOffset(int offset) {
    moveToOffset(offset, false);
  }

  @Override
  public void moveToOffset(final int offset, boolean locateBeforeSoftWrap) {
    int hostOffset = myEditorWindow.getDocument().injectedToHost(offset);
    myDelegate.moveToOffset(hostOffset, locateBeforeSoftWrap);
  }

  @Override
  @NotNull
  public LogicalPosition getLogicalPosition() {
    LogicalPosition hostPos = myDelegate.getLogicalPosition();
    return myEditorWindow.hostToInjected(hostPos);
  }

  @Override
  @NotNull
  public VisualPosition getVisualPosition() {
    LogicalPosition logicalPosition = getLogicalPosition();
    return myEditorWindow.logicalToVisualPosition(logicalPosition);
  }

  @Override
  public int getOffset() {
    return myEditorWindow.getDocument().hostToInjected(myDelegate.getOffset());
  }

  @Override
  public boolean isUpToDate() {
    return myDelegate.isUpToDate();
  }

  private final ListenerWrapperMap<CaretListener> myCaretListeners = new ListenerWrapperMap<CaretListener>();
  @Override
  public void addCaretListener(@NotNull final CaretListener listener) {
    CaretListener wrapper = new CaretListener() {
      @Override
      public void caretPositionChanged(CaretEvent e) {
        if (!myEditorWindow.getDocument().isValid()) return; // injected document can be destroyed by now
        CaretEvent event = new CaretEvent(myEditorWindow, createInjectedCaret(e.getCaret()),
                                          myEditorWindow.hostToInjected(e.getOldPosition()),
                                          myEditorWindow.hostToInjected(e.getNewPosition()));
        listener.caretPositionChanged(event);
      }
    };
    myCaretListeners.registerWrapper(listener, wrapper);
    myDelegate.addCaretListener(wrapper);
  }

  @Override
  public void removeCaretListener(@NotNull final CaretListener listener) {
    CaretListener wrapper = myCaretListeners.removeWrapper(listener);
    if (wrapper != null) {
      myDelegate.removeCaretListener(wrapper);
    }
  }

  public void disposeModel() {
    for (CaretListener wrapper : myCaretListeners.wrappers()) {
      myDelegate.removeCaretListener(wrapper);
    }
    myCaretListeners.clear();
  }

  @Override
  public int getVisualLineStart() {
    return myEditorWindow.getDocument().hostToInjected(myDelegate.getVisualLineStart());
  }

  @Override
  public int getVisualLineEnd() {
    return myEditorWindow.getDocument().hostToInjected(myDelegate.getVisualLineEnd());
  }

  @Override
  public TextAttributes getTextAttributes() {
    return myDelegate.getTextAttributes();
  }

  @Override
  public boolean supportsMultipleCarets() {
    return myDelegate.supportsMultipleCarets();
  }

  @NotNull
  @Override
  public Caret getCurrentCaret() {
    return createInjectedCaret(myDelegate.getCurrentCaret());
  }

  @NotNull
  @Override
  public Caret getPrimaryCaret() {
    return createInjectedCaret(myDelegate.getPrimaryCaret());
  }

  @NotNull
  @Override
  public Collection<Caret> getAllCarets() {
    Collection<Caret> hostCarets = myDelegate.getAllCarets();
    Collection<Caret> carets = new ArrayList<Caret>(hostCarets.size());
    for (Caret hostCaret : hostCarets) {
      carets.add(createInjectedCaret(hostCaret));
    }
    return carets;
  }

  @Nullable
  @Override
  public Caret getCaretAt(@NotNull VisualPosition pos) {
    LogicalPosition hostPos = myEditorWindow.injectedToHost(myEditorWindow.visualToLogicalPosition(pos));
    Caret caret = myDelegate.getCaretAt(myHostEditor.logicalToVisualPosition(hostPos));
    return createInjectedCaret(caret);
  }

  @Nullable
  @Override
  public Caret addCaret(@NotNull VisualPosition pos) {
    LogicalPosition hostPos = myEditorWindow.injectedToHost(myEditorWindow.visualToLogicalPosition(pos));
    Caret caret = myDelegate.addCaret(myHostEditor.logicalToVisualPosition(hostPos));
    return createInjectedCaret(caret);
  }

  @Override
  public boolean removeCaret(@NotNull Caret caret) {
    if (caret instanceof InjectedCaret) {
      caret = ((InjectedCaret)caret).myDelegate;
    }
    return myDelegate.removeCaret(caret);
  }

  @Override
  public void removeSecondaryCarets() {
    myDelegate.removeSecondaryCarets();
  }

  @Override
  public void setCarets(@NotNull List<LogicalPosition> caretPositions, @NotNull List<? extends Segment> selections) {
    List<LogicalPosition> convertedPositions = new ArrayList<LogicalPosition>(caretPositions);
    for (LogicalPosition position : caretPositions) {
      convertedPositions.add(myEditorWindow.injectedToHost(position));
    }
    List<Segment> convertedSelections = new ArrayList<Segment>(selections.size());
    for (Segment selection : selections) {
      convertedSelections.add(new TextRange(myEditorWindow.getDocument().injectedToHost(selection.getStartOffset()),
                                            myEditorWindow.getDocument().injectedToHost(selection.getEndOffset())));
    }
    myDelegate.setCarets(convertedPositions, convertedSelections);
  }

  private InjectedCaret createInjectedCaret(Caret caret) {
    return caret == null ? null : new InjectedCaret(myEditorWindow, caret);
  }

  @Override
  public void runForEachCaret(final @NotNull CaretAction action) {
    myDelegate.runForEachCaret(new CaretAction() {
      @Override
      public void perform(Caret caret) {
        action.perform(createInjectedCaret(caret));
      }
    });
  }

  @Override
  public void runBatchCaretOperation(@NotNull Runnable runnable) {
    myDelegate.runBatchCaretOperation(runnable);
  }
}
