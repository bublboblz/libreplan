/*
 * This file is part of NavalPlan
 *
 * Copyright (C) 2009-2010 Fundación para o Fomento da Calidade Industrial e
 *                         Desenvolvemento Tecnolóxico de Galicia
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.zkoss.ganttz;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.commons.lang.Validate;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.zkoss.ganttz.adapters.IDisabilityConfiguration;
import org.zkoss.ganttz.data.Milestone;
import org.zkoss.ganttz.data.Task;
import org.zkoss.ganttz.data.Task.IReloadResourcesTextRequested;
import org.zkoss.ganttz.data.TaskContainer;
import org.zkoss.ganttz.data.constraint.Constraint;
import org.zkoss.ganttz.data.constraint.Constraint.IConstraintViolationListener;
import org.zkoss.lang.Objects;
import org.zkoss.zk.au.AuRequest;
import org.zkoss.zk.au.AuService;
import org.zkoss.zk.au.out.AuInvoke;
import org.zkoss.zk.mesg.MZk;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.UiException;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zk.ui.ext.AfterCompose;
import org.zkoss.zk.ui.sys.ContentRenderer;
import org.zkoss.zul.Div;

/**
 * Graphical component which represents a {@link Task}.
 *
 * @author Javier Morán Rúa <jmoran@igalia.com>
 */
public class TaskComponent extends Div implements AfterCompose {

    private static final Log LOG = LogFactory.getLog(TaskComponent.class);

    private static final int HEIGHT_PER_TASK = 10;
    private static final int CONSOLIDATED_MARK_HALF_WIDTH = 3;


    private static Pattern pixelsSpecificationPattern = Pattern
            .compile("\\s*(\\d+)px\\s*;?\\s*");

    protected final IDisabilityConfiguration disabilityConfiguration;

    private PropertyChangeListener criticalPathPropertyListener;

    public static TaskComponent asTaskComponent(Task task,
            IDisabilityConfiguration disabilityConfiguration,
            boolean isTopLevel) {
        final TaskComponent result;
        if (task.isContainer()) {
            result = TaskContainerComponent.asTask((TaskContainer) task,
                    disabilityConfiguration);
        } else if (task instanceof Milestone) {
            result = new MilestoneComponent(task, disabilityConfiguration);
        } else {
            result = new TaskComponent(task, disabilityConfiguration);
        }
        result.isTopLevel = isTopLevel;
        return TaskRow.wrapInRow(result);
    }

    public static TaskComponent asTaskComponent(Task task,
            IDisabilityConfiguration disabilityConfiguration) {
        return asTaskComponent(task, disabilityConfiguration, true);
    }

    private IReloadResourcesTextRequested reloadResourcesTextRequested;

    public TaskComponent(Task task,
            IDisabilityConfiguration disabilityConfiguration) {
        setHeight(HEIGHT_PER_TASK + "px");
        setContext("idContextMenuTaskAssignment");
        this.task = task;
        setClass(calculateCSSClass());

        setId(UUID.randomUUID().toString());
        this.disabilityConfiguration = disabilityConfiguration;
        taskViolationListener = new IConstraintViolationListener<Date>() {

            @Override
            public void constraintViolated(Constraint<Date> constraint,
                    Date value) {
                // TODO mark graphically task as violated
            }
        };
        this.task.addConstraintViolationListener(taskViolationListener);
        reloadResourcesTextRequested = new IReloadResourcesTextRequested() {

            @Override
            public void reloadResourcesTextRequested() {
                if (canShowResourcesText()) {
                    smartUpdate("resourcesText", getResourcesText());
                }
                String cssClass = calculateCSSClass();

                response("setClass", new AuInvoke(TaskComponent.this,
                        "setClass", cssClass));

                // FIXME: Refactorize to another listener
                updateDeadline();
            }

        };
        this.task.addReloadListener(reloadResourcesTextRequested);
        setAuService(new AuService(){
            public boolean service(AuRequest request, boolean everError){
                String command = request.getCommand();
                final TaskComponent ta;

                if (command.equals("onUpdatePosition")){
                    ta = retrieveTaskComponent(request);

                    ta.doUpdatePosition((Integer) retrieveData(request, "left"), (Integer) retrieveData(request, "top"));
                    Events.postEvent(new Event(getId(), ta, request.getData()));

                    return true;
                }
                if (command.equals("onUpdateWidth")){
                    ta = retrieveTaskComponent(request);

                    ta.doUpdateSize((Integer) retrieveData(request, "width"));
                    Events.postEvent(new Event(getId(), ta, request.getData()));

                    return true;
                }
                if (command.equals("onAddDependency")){
                    ta = retrieveTaskComponent(request);

                    ta.doAddDependency((String) retrieveData(request, "dependencyId"));
                    Events.postEvent(new Event(getId(), ta, request.getData()));

                    return true;
                }
                return false;
            }

            private TaskComponent retrieveTaskComponent(AuRequest request){
                final TaskComponent ta = (TaskComponent) request.getComponent();

                if (ta == null) {
                    throw new UiException(MZk.ILLEGAL_REQUEST_COMPONENT_REQUIRED,
                            this);
                }

                return ta;
            }

            private Object retrieveData(AuRequest request, String key){
                Object value = request.getData().get(key);
                if ( value == null)
                    throw new UiException(MZk.ILLEGAL_REQUEST_WRONG_DATA,
                            new Object[] { key, this });

                return value;
            }
        });
    }

    /* Generate CSS class attribute depending on task properties */
    protected String calculateCSSClass() {
        String cssClass = isSubcontracted() ? "box subcontracted-task"
                : "box standard-task";
        cssClass += isResizingTasksEnabled() ? " yui-resize" : "";
        if (isContainer()) {
            cssClass += task.isExpanded() ? " expanded" : " closed ";
        }
        cssClass += task.isInCriticalPath() ? " critical" : "";
        cssClass += " " + task.getAssignedStatus();
        if (task.isLimiting()) {
            cssClass += task.isLimitingAndHasDayAssignments() ? " limiting-assigned "
                    : " limiting-unassigned ";
        }
        return cssClass;
    }


    protected void updateClass() {
        setSclass(calculateCSSClass());
    }

    public final void afterCompose() {
        updateProperties();
        if (propertiesListener == null) {
            propertiesListener = new PropertyChangeListener() {

                @Override
                public void propertyChange(PropertyChangeEvent evt) {
                    if (isInPage()) {
                        updateProperties();
                    }
                }
            };
        }
        this.task
                .addFundamentalPropertiesChangeListener(propertiesListener);

        if (criticalPathPropertyListener == null) {
            criticalPathPropertyListener = new PropertyChangeListener() {

                @Override
                public void propertyChange(PropertyChangeEvent evt) {
                    updateClass();
                }

            };
        }
        this.task
                .addCriticalPathPropertyChangeListener(criticalPathPropertyListener);

        updateClass();
    }

    /**
     * Note: This method is intended to be overridden.
     */
    protected boolean canShowResourcesText() {
        return true;
    }

    private String _color;

    private boolean isTopLevel;

    private final Task task;
    private transient PropertyChangeListener propertiesListener;

    private IConstraintViolationListener<Date> taskViolationListener;

    public TaskRow getRow() {
        if (getParent() == null) {
            throw new IllegalStateException(
                    "the TaskComponent should have been wraped by a "
                            + TaskRow.class.getName());
        }
        return (TaskRow) getParent();
    }

    public Task getTask() {
        return task;
    }

    public String getTaskName() {
        return task.getName();
    }

    public String getLength() {
        return null;
    }

    public boolean isResizingTasksEnabled() {
        return (disabilityConfiguration != null)
                && disabilityConfiguration.isResizingTasksEnabled()
                && !task.isSubcontracted() && task.canBeExplicitlyResized();
    }

    public boolean isMovingTasksEnabled() {
        return (disabilityConfiguration != null)
                && disabilityConfiguration.isMovingTasksEnabled()
                && task.canBeExplicitlyMoved();
    }

    void doUpdatePosition(int leftX, int topY) {
        Date startBeforeMoving = this.task.getBeginDate();
        this.task.moveTo(getMapper().toDate(leftX));
        boolean remainsInOriginalPosition = this.task.getBeginDate().equals(
                startBeforeMoving);
        if (remainsInOriginalPosition) {
            updateProperties();
        }
    }

    void doUpdateSize(int size) {
        this.task.setLengthMilliseconds(getMapper().toMilliseconds(size));
        updateWidth();
    }

    void doAddDependency(String destinyTaskId) {
        getTaskList().addDependency(this,
                ((TaskComponent) getFellow(destinyTaskId)));
    }

    public String getColor() {
        return _color;
    }

    public void setColor(String color) {

        if ((color != null) && (color.length() == 0)) {
            color = null;
        }

        if (!Objects.equals(_color, color)) {
            _color = color;
        }
    }

    /*
     * We override the method of renderProperties to put the color property as part
     * of the style
     */
    protected void renderProperties(ContentRenderer renderer) throws IOException{
        if(getColor() != null)
            setStyle("background-color : " +  getColor());

        setWidgetAttribute("movingTasksEnabled",((Boolean)isMovingTasksEnabled()).toString());
        setWidgetAttribute("resizingTasksEnabled", ((Boolean)isResizingTasksEnabled()).toString());

        /*We can't use setStyle because of restrictions
         * involved with UiVisualizer#getResponses and the
         * smartUpdate method (when the request is asynchronous) */
        render(renderer, "style", "position : absolute");

        render(renderer, "_labelsText", getLabelsText());
        render(renderer, "_resourcesText", getResourcesText());
        render(renderer, "_tooltipText", getTooltipText());

        super.renderProperties(renderer);
    }

    /*
     * We send a response to the client to create the arrow we are going to use
     * to create the dependency
     */

    public void addDependency() {
        response("depkey", new AuInvoke(this, "addDependency"));
    }

    private IDatesMapper getMapper() {
        return getTaskList().getMapper();
    }

    public TaskList getTaskList() {
        return getRow().getTaskList();
    }

    @Override
    public void setParent(Component parent) {
        Validate.isTrue(parent == null || parent instanceof TaskRow);
        super.setParent(parent);
    }

    public final void zoomChanged() {
        updateProperties();
    }

    public void updateProperties() {
        if (!isInPage()) {
            return;
        }
        setLeft("0");
        setLeft(getMapper().toPixels(this.task.getBeginDate()) + "px");
        updateWidth();
        smartUpdate("name", this.task.getName());
        DependencyList dependencyList = getDependencyList();
        if (dependencyList != null) {
            dependencyList.redrawDependenciesConnectedTo(this);
        }
        updateDeadline();
        updateCompletionIfPossible();
        updateClass();
    }

    private void updateWidth() {
        setWidth("0");
        setWidth(getMapper().toPixels(this.task.getLengthMilliseconds())
                + "px");
    }

    private void updateDeadline() {
        if (task.getDeadline() != null) {
            String position = getMapper().toPixels(task.getDeadline()) + "px";
            response(null, new AuInvoke(this, "moveDeadline", position));
        } else {
            // Move deadline out of visible area
            response(null, new AuInvoke(this, "moveDeadline","-100px"));
        }

        if (task.getConsolidatedline() != null) {
            String position = (getMapper().toPixels(task.getConsolidatedline()) - CONSOLIDATED_MARK_HALF_WIDTH)
                    + "px";
            response(null, new AuInvoke(this, "moveConsolidatedline", position));
        } else {
            // Move consolidated line out of visible area
            response(null, new AuInvoke(this, "moveConsolidatedline", "-100px"));
        }
    }

    public void updateCompletionIfPossible() {
        try {
            updateCompletion();
        } catch (Exception e) {
            LOG.error("failure at updating completion", e);
        }
    }

    private void updateCompletion() {
        long beginMilliseconds = this.task.getBeginDate().getTime();

        long hoursAdvanceEndMilliseconds = this.task.getHoursAdvanceEndDate()
                .getTime()
                - beginMilliseconds;
        if (hoursAdvanceEndMilliseconds < 0) {
            hoursAdvanceEndMilliseconds = 0;
        }
        String widthHoursAdvancePercentage = getMapper().toPixels(
                hoursAdvanceEndMilliseconds)
                + "px";
        response(null, new AuInvoke(this, "resizeCompletionAdvance",
                widthHoursAdvancePercentage));

        long advanceEndMilliseconds = this.task.getAdvanceEndDate()
                .getTime()
                - beginMilliseconds;
        if (advanceEndMilliseconds < 0) {
            advanceEndMilliseconds = 0;
        }
        String widthAdvancePercentage = getMapper().toPixels(
                advanceEndMilliseconds)
                + "px";
        response(null, new AuInvoke(this, "resizeCompletion2Advance",
                widthAdvancePercentage));
    }

    public void updateTooltipText() {
        smartUpdate("taskTooltipText", task.updateTooltipText());
    }

    private DependencyList getDependencyList() {
        return getGanntPanel().getDependencyList();
    }

    private GanttPanel getGanntPanel() {
        return getTaskList().getGanttPanel();
    }

    private boolean isInPage() {
        return getPage() != null;
    }

    void publishTaskComponents(Map<Task, TaskComponent> resultAccumulated) {
        resultAccumulated.put(getTask(), this);
        publishDescendants(resultAccumulated);
    }

    protected void publishDescendants(Map<Task, TaskComponent> resultAccumulated) {

    }

    protected void remove() {
        this.getRow().detach();
        task.removeReloadListener(reloadResourcesTextRequested);
    }

    public boolean isTopLevel() {
        return isTopLevel;
    }

    public String getTooltipText() {
        return task.getTooltipText();
    }

    public String getLabelsText() {
        return task.getLabelsText();
    }

    public String getLabelsDisplay() {
        Planner planner = getTaskList().getGanttPanel().getPlanner();
        return planner.isShowingLabels() ? "inline" : "none";
    }

    public String getResourcesText() {
        return task.getResourcesText();
    }

    public String getResourcesDisplay() {
        Planner planner = getTaskList().getGanttPanel().getPlanner();
        return planner.isShowingResources() ? "inline" : "none";
    }

    public boolean isSubcontracted() {
        return task.isSubcontracted();
    }

    public boolean isContainer() {
        return task.isContainer();
    }

    @Override
    public String toString() {
        return task.toString();
    }

}
