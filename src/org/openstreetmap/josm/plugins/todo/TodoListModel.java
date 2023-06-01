// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.todo;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.swing.AbstractListModel;
import javax.swing.DefaultListSelectionModel;

import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.PrimitiveId;
import org.openstreetmap.josm.data.osm.event.AbstractDatasetChangedEvent;
import org.openstreetmap.josm.data.osm.event.DataChangedEvent;
import org.openstreetmap.josm.data.osm.event.DataSetListener;
import org.openstreetmap.josm.data.osm.event.NodeMovedEvent;
import org.openstreetmap.josm.data.osm.event.PrimitivesAddedEvent;
import org.openstreetmap.josm.data.osm.event.PrimitivesRemovedEvent;
import org.openstreetmap.josm.data.osm.event.RelationMembersChangedEvent;
import org.openstreetmap.josm.data.osm.event.TagsChangedEvent;
import org.openstreetmap.josm.data.osm.event.WayNodesChangedEvent;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.util.TableHelper;

/**
 * The list model for the todo list items.
 *
 * The model also maintains a list of already completed items
 *
 */
public class TodoListModel extends AbstractListModel<TodoListItem> implements DataSetListener {

    private final List<TodoListItem> todoList = new ArrayList<>();
    private final Collection<TodoListItem> doneList = new HashSet<>();
    private final DefaultListSelectionModel selectionModel;

    /**
     * Create a new model
     * @param selectionModel The selection model to use
     */
    public TodoListModel(DefaultListSelectionModel selectionModel) {
        this.selectionModel = selectionModel;
    }

    @Override
    public TodoListItem getElementAt(int index) {
        return todoList.get(index);
    }

    @Override
    public int getSize() {
        return todoList.size();
    }

    public boolean isSelectionEmpty() {
        return selectionModel.isSelectionEmpty();
    }

    public int getDoneSize() {
        return doneList.size();
    }

    public synchronized Collection<TodoListItem> getSelected() {
        return IntStream.range(0, getSize())
                .filter(selectionModel::isSelectedIndex)
                .mapToObj(todoList::get)
                .collect(Collectors.toSet());
    }

    public Collection<TodoListItem> getItemsForPrimitives(Collection<? extends OsmPrimitive> primitives) {
        final ArrayList<TodoListItem> items = new ArrayList<>(todoList.size());
        final Map<PrimitiveId, OsmPrimitive> primitiveMap = new HashMap<>(primitives.size());
        primitives.forEach(primitive -> primitiveMap.put(primitive.getPrimitiveId(), primitive));
        for (TodoListItem todoListItem : todoList) {
            final PrimitiveId pid = todoListItem.primitive().getPrimitiveId();
            if (primitiveMap.containsKey(pid)
                && todoListItem.layer().getDataSet().equals(primitiveMap.get(pid).getDataSet())) {
                items.add(todoListItem);
            }
        }
        items.trimToSize();
        return items;
    }

    public List<TodoListItem> getTodoList() {
        return todoList;
    }

    public void incrementSelection() {
        int idx;
        if (getSize() == 0)
            return;
        if (selectionModel.isSelectionEmpty())
            idx = 0;
        else
            idx = selectionModel.getMinSelectionIndex() + 1;

        if (idx > getSize()-1)
            idx = getSize()-1;

        selectionModel.setSelectionInterval(idx, idx);
    }

    public void addItems(Collection<TodoListItem> items) {
        if (items == null || items.isEmpty())
            return;
        doneList.removeAll(items);
        int size = getSize();
        if (size == 0) {
            todoList.addAll(items);
            super.fireIntervalAdded(this, 0, getSize()-1);
            selectionModel.setSelectionInterval(0, 0);
        } else {
            final List<TodoListItem> tempList = new ArrayList<>(items.size());
            for (TodoListItem item: items) {
                if (!todoList.contains(item))
                    tempList.add(item);
            }
            todoList.addAll(tempList);
            super.fireIntervalAdded(this, size, getSize()-1);
        }
    }

    public boolean purgeLayerItems(OsmDataLayer layer) {
        int n = getSize() - 1;
        boolean changed = todoList.removeIf(i -> layer.equals(i.layer()));
        if (changed) {
            super.fireIntervalRemoved(this, 0, n);
            selectionModel.setSelectionInterval(0, 0);
        }
        changed |= doneList.removeIf(i -> layer.equals(i.layer()));
        return changed;
    }

    public void markSelected() {
        if (selectionModel.isSelectionEmpty() || getSize() == 0)
            return;
        int sel = selectionModel.getMinSelectionIndex();
        if (sel >= todoList.size())
            return;
        doneList.add(todoList.remove(sel));
        super.fireIntervalRemoved(this, sel, sel);
        if (sel == getSize())
            sel = 0;
        selectionModel.setSelectionInterval(sel, sel);
    }

    public synchronized void setSelected(Collection<TodoListItem> sel) {
        TableHelper.setSelectedIndices(selectionModel,
                sel != null ? sel.stream().mapToInt(todoList::indexOf) : IntStream.empty());
    }

    public void markAll() {
        int size = getSize();
        if (size == 0)
            return;
        doneList.addAll(todoList);
        todoList.clear();
        super.fireIntervalRemoved(this, 0, size-1);
    }

    public void removeItems(Collection<TodoListItem> items) {
        if (items == null || items.isEmpty())
            return;

        int size = getSize();

        todoList.removeAll(items);
        doneList.removeAll(items);

        super.fireIntervalRemoved(this, 0, size-1);
    }

    /**
     * Mark items as done
     * @param items The items that are done
     */
    public void markItems(Collection<TodoListItem> items) {
        if (items == null || items.isEmpty())
            return;
        int size = getSize();
        if (size == 0)
            return;

        int sel = selectionModel.getMinSelectionIndex();

        this.selectionModel.setValueIsAdjusting(true);
        // For very large lists, the indexOf operation becomes very expensive due to the equals method
        final Map<TodoListItem, Integer> indexList = new HashMap<>(todoList.size());
        for (int i = 0; i < todoList.size(); i++) {
            indexList.put(todoList.get(i), i);
        }
        final int[] indices = items.stream().parallel().mapToInt(item -> {
            Integer i = indexList.get(item);
            if (i != null) {
                return i;
            }
            return todoList.indexOf(item);
        }).sorted().toArray();
        final List<TodoListItem> tempDoneList = new ArrayList<>(indices.length);
        for (int index = indices.length - 1; index >= 0; index--) {
            final TodoListItem item = todoList.get(index);
            todoList.remove(index);
            tempDoneList.add(item);
            if (sel > index)
                sel--;
        }
        doneList.addAll(tempDoneList);
        if (sel >= getSize())
            sel = 0;
        this.selectionModel.setValueIsAdjusting(false);
        super.fireIntervalRemoved(this, 0, size-1);
        selectionModel.setSelectionInterval(sel, sel);
    }

    /**
     * Clear the done and todo lists
     */
    public void clear() {
        int size = getSize();
        doneList.clear();
        todoList.clear();
        if (size > 0)
            super.fireIntervalRemoved(this, 0, size-1);
    }

    /**
     * Clear the done list by moving the todo items to the todo list
     */
    public void unmarkAll() {
        if (getDoneSize() == 0)
            return;
        int size = getSize();
        if (size == 0) {
            todoList.addAll(doneList);
            doneList.clear();
            super.fireIntervalAdded(this, 0, getSize()-1);
            selectionModel.setSelectionInterval(0, 0);
        } else {
            todoList.addAll(doneList);
            doneList.clear();
            super.fireIntervalAdded(this, size, getSize()-1);
        }
    }

    public String getSummary() {
        int totalSize = getSize()+getDoneSize();
        if (getSize() == 0 && getDoneSize() == 0)
            return tr("Todo list");
        else return tr("Todo list {0}/{1} ({2}%)", getDoneSize(), totalSize, 100.0*getDoneSize()/totalSize);
    }

    /**
     * Triggers a refresh of the view for all items in {@code toUpdate}
     * which are currently displayed in the view
     *
     * @param toUpdate the collection of items to update
     */
    public synchronized void update(Collection<? extends TodoListItem> toUpdate) {
        if (toUpdate == null) return;
        if (toUpdate.isEmpty()) return;
        Collection<TodoListItem> sel = getSelected();
        for (TodoListItem p: toUpdate) {
            int i = todoList.indexOf(p);
            if (i >= 0) {
                super.fireContentsChanged(this, i, i);
            }
        }
        if (!sel.equals(getSelected())) {
            setSelected(sel);
        }
    }

    @Override
    public void primitivesAdded(PrimitivesAddedEvent event) {
        // ignored
    }

    @Override
    public void primitivesRemoved(PrimitivesRemovedEvent event) {
        removeItems(getItemsForPrimitives(event.getPrimitives()));
    }

    @Override
    public void tagsChanged(TagsChangedEvent event) {
        update(getItemsForPrimitives(event.getPrimitives()));
    }

    @Override
    public void nodeMoved(NodeMovedEvent event) {
        update(getItemsForPrimitives(event.getPrimitives()));
    }

    @Override
    public void wayNodesChanged(WayNodesChangedEvent event) {
        update(getItemsForPrimitives(event.getPrimitives()));
    }

    @Override
    public void relationMembersChanged(RelationMembersChangedEvent event) {
        update(getItemsForPrimitives(event.getPrimitives()));
    }

    @Override
    public void otherDatasetChange(AbstractDatasetChangedEvent event) {
        update(getItemsForPrimitives(event.getPrimitives()));
    }

    @Override
    public void dataChanged(DataChangedEvent event) {
        // We cannot just call event.getPrimitives since some events just return all primitives in the dataset.
        final Collection<OsmPrimitive> changedPrimitives;
        final List<AbstractDatasetChangedEvent> changeEvents = event.getEvents();
        if (changeEvents != null) {
            changedPrimitives = new HashSet<>();
            for (AbstractDatasetChangedEvent e : changeEvents) {
                if (e instanceof PrimitivesRemovedEvent) {
                    primitivesRemoved((PrimitivesRemovedEvent) e);
                }
                changedPrimitives.addAll(e.getPrimitives());
            }
        } else {
            changedPrimitives = event.getPrimitives();
        }
        update(getItemsForPrimitives(changedPrimitives));
    }
}
