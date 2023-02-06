package de.intranda.goobi.plugins;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.lang.StringUtils;
import org.goobi.beans.Process;
import org.goobi.beans.Project;
import org.goobi.beans.Step;
import org.goobi.beans.Usergroup;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.BeanHelper;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.ScriptThreadWithoutHibernate;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.enums.StepStatus;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.ProcessManager;
import de.sub.goobi.persistence.managers.ProjectManager;
import de.sub.goobi.persistence.managers.StepManager;
import de.sub.goobi.persistence.managers.UsergroupManager;
import lombok.Data;
import lombok.extern.log4j.Log4j;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DigitalDocument;
import ugh.dl.Fileformat;
import ugh.dl.Prefs;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;

@PluginImplementation
@Data
@Log4j
public class ChangeWorkflowPlugin implements IStepPluginVersion2 {

    private Step step;
    private Process process;

    private PluginGuiType pluginGuiType = PluginGuiType.NONE;
    private String pagePath;
    private PluginType type = PluginType.Step;

    private String title = "intranda_step_changeWorkflow";
    private List<HierarchicalConfiguration> changes;

    @Override
    public void initialize(Step step, String returnPath) {
        this.step = step;
        this.process = step.getProzess();
        this.pagePath = returnPath;

        SubnodeConfiguration config = ConfigPlugins.getProjectAndStepConfig(title, step);
        changes = config.configurationsAt("./change");
    }

    @Override
    public PluginReturnValue run() {
        boolean anyConditionMatched = false;
        List<String> automaticRunSteps = new ArrayList<>();

        // run through all configured changes
        for (HierarchicalConfiguration configChanges : changes) {

            // load value via variable replacer
            String variable = configChanges.getString("./propertyName");
            String preferedValue = configChanges.getString("./propertyValue", "");
            String condition = configChanges.getString("./propertyCondition", "is");

            log.debug("propertyName = " + variable);
            log.debug("propertyValue = " + preferedValue);
            log.debug("propertyCondition = " + condition);

            String realValue = null;

            // read the real value from the variable replacer
            try {
                realValue = getRealValue(process, variable);

            } catch (Exception e2) {
                log.error("An exception occurred while reading the metadata file for process with ID " + step.getProcessId(), e2);
                Helper.addMessageToProcessJournal(step.getProzess().getId(), LogType.ERROR, "error reading metadata file", "http step");
                return PluginReturnValue.ERROR;
            }

            log.debug("realValue = " + realValue);

            String processTemplateName = configChanges.getString("./workflow");
            String projectName = configChanges.getString("./project");

            log.debug("processTemplateName = " + processTemplateName);
            log.debug("projectName = " + projectName);

            List<String> stepsToOpen = Arrays.asList(configChanges.getStringArray("./steps[@type='open']/title"));
            List<String> stepsToDeactivate = Arrays.asList(configChanges.getStringArray("./steps[@type='deactivate']/title"));
            List<String> stepsToClose = Arrays.asList(configChanges.getStringArray("./steps[@type='close']/title"));
            List<String> stepsToLock = Arrays.asList(configChanges.getStringArray("./steps[@type='lock']/title"));
            List<String> stepsToRunAutomatic = Arrays.asList(configChanges.getStringArray("./steps[@type='run']/title"));

            Map<String, List<String>> userGroupChanges = new HashMap<>();

            List<HierarchicalConfiguration> userGroupDefinition = configChanges.configurationsAt("./usergroups");
            for (HierarchicalConfiguration def : userGroupDefinition) {
                String stepTitle = def.getString("@step");
                List<String> groups = Arrays.asList(def.getStringArray("usergroup"));
                userGroupChanges.put(stepTitle, groups);
            }

            List<String> logError = Arrays.asList(configChanges.getStringArray("./log[@type='error']"));
            List<String> logInfo = Arrays.asList(configChanges.getStringArray("./log[@type='info']"));
            List<String> logUser = Arrays.asList(configChanges.getStringArray("./log[@type='user']"));
            List<String> logDebug = Arrays.asList(configChanges.getStringArray("./log[@type='debug']"));
            
            // 1.) check if property name is set
            if (StringUtils.isBlank(variable)) {
                log.error("Cannot find property, abort");
                return PluginReturnValue.ERROR;
            }

            // 2.) check if property and value exist in process
            boolean conditionMatches = checkCondition(condition, realValue, preferedValue);
            anyConditionMatched = anyConditionMatched || conditionMatches;

            log.debug("conditionMatches = " + conditionMatches);
            log.debug("anyConditionMatched = " + anyConditionMatched);

            if (conditionMatches) {
                // add new automatic steps
                automaticRunSteps.addAll(stepsToRunAutomatic);

                // change process template
                if (StringUtils.isNotBlank(processTemplateName)) {
                    changeProcessTemplate(process, processTemplateName);
                }

                // change project
                if (StringUtils.isNotBlank(projectName)) {
                    changeProject(process, projectName);
                }

                // add log entries into the journal (process log)
                List<List<String>> logLists = Arrays.asList(logError, logInfo, logUser, logDebug);
                LogType[] logTypeValues = new LogType[] { LogType.ERROR, LogType.INFO, LogType.USER, LogType.DEBUG };
                addAllLogEntries(process, logLists, logTypeValues);

                // 3.) run through tasks and change the status
                List<List<String>> stepsLists = Arrays.asList(stepsToOpen, stepsToDeactivate, stepsToClose, stepsToLock);
                StepStatus[] statusValues = new StepStatus[] { StepStatus.OPEN, StepStatus.DEACTIVATED, StepStatus.DONE, StepStatus.LOCKED };
                changeAllStatus(process, stepsLists, statusValues, userGroupChanges);
            }
        }

        // save the process if any change was done
        if (anyConditionMatched) {
            log.debug("anyConditionMatched = " + anyConditionMatched);
            try {
                saveProcess(process, automaticRunSteps);
            } catch (DAOException e) {
                log.error(e);
                return PluginReturnValue.ERROR;
            }
        }

        return PluginReturnValue.FINISH;
    }

    private String getRealValue(Process process, String variable) throws ReadException, IOException, SwapException, PreferencesException {
        Prefs prefs = process.getRegelsatz().getPreferences();
        Fileformat ff = process.readMetadataFile();
        if (ff == null) {
            log.error("Metadata file is not readable for process with ID " + step.getProcessId());
            Helper.addMessageToProcessJournal(step.getProzess().getId(), LogType.ERROR, "Metadata file is not readable", "http step");
            throw new IOException("Metadata file is not readable.");
        }
        DigitalDocument dd = ff.getDigitalDocument();
        VariableReplacer replacer = new VariableReplacer(dd, prefs, step.getProzess(), step);

        String realValue = replacer.replace(variable);
        if (realValue.equals(variable)) {
            realValue = null;
        }

        return realValue;
    }

    private boolean checkCondition(String condition, String realValue, String preferedValue) {
        log.debug("checking condition: " + condition);
        switch (condition) {
            case "missing": 
                return realValue == null || realValue.trim().equals("");

            case "available":
                return realValue != null && !realValue.trim().equals("");

            case "is":
                return realValue != null && realValue.trim().equals(preferedValue);

            case "not":
                return realValue == null || !realValue.trim().equals(preferedValue);

            default:
                return false;
        }
    }

    private void changeProcessTemplate(Process process, String processTemplateName) {
        log.debug("changing processTemplateName: " + processTemplateName);
        Process template = ProcessManager.getProcessByExactTitle(processTemplateName);
        if (template != null) {
            BeanHelper helper = new BeanHelper();
            helper.changeProcessTemplate(process, template);
            // check, if the new workflow has the current step in it
            for (Step newTask : process.getSchritteList()) {
                if (StringUtils.isNotBlank(newTask.getStepPlugin()) && title.equals(newTask.getStepPlugin())
                        && newTask.getTitel().equals(this.step.getTitel())) {
                    // if this is the case, use old creation date and user
                    newTask.setBearbeitungsbeginn(this.step.getBearbeitungsbeginn());
                    newTask.setBearbeitungsbenutzer(this.step.getBearbeitungsbenutzer());
                    // finish the task
                    newTask.setBearbeitungsende(new Date());
                    newTask.setBearbeitungsstatusEnum(StepStatus.DONE);
                }
            }
        }
    }

    private void changeProject(Process process, String projectName) {
        log.debug("changing projectName: " + projectName);
        try {
            Project newProject = ProjectManager.getProjectByName(projectName);
            if (newProject != null) {
                process.setProjekt(newProject);
                process.setProjectId(newProject.getId());
                log.debug("title of newProject = " + newProject.getTitel());
                log.debug("id of newProject = " + newProject.getId());
            }
        } catch (DAOException e) {
            log.error(e);
        }
    }

    private void addAllLogEntries(Process process, List<List<String>> logLists, LogType[] logTypeValues) {
        if (logLists.size() != logTypeValues.length) {
            // error here since these two must match
        }
        int count = 0;
        for (List<String> logList : logLists) {
            addLogEntries(process, logList, logTypeValues[count]);
            ++count;
        }
    }

    private void addLogEntries(Process process, List<String> logList, LogType logType) {
        for (String s : logList) {
            Helper.addMessageToProcessLog(process.getId(), logType, s);
        }
    }

    private void changeAllStatus(Process process, List<List<String>> stepsLists, StepStatus[] statusValues,
            Map<String, List<String>> userGroupChanges) {
        if (stepsLists.size() != statusValues.length) {
            // error here since these two must match
        }
        for (Step currentStep : process.getSchritteList()) {
            // change step status
            int count = 0;
            for (List<String> stepsList : stepsLists) {
                changeStatus(currentStep, stepsList, statusValues[count]);
                ++count;
            }

            // change user groups 
            changeUserGroups(currentStep, userGroupChanges);
        }
    }

    private void changeStatus(Step currentStep, List<String> stepsList, StepStatus status) {
        String currentStepName = currentStep.getTitel();
        for (String taskName : stepsList) {
            if (currentStepName.equals(taskName)) {
                currentStep.setBearbeitungsstatusEnum(status);
            }
        }
    }

    private void changeUserGroups(Step currentStep, Map<String, List<String>> userGroupChanges) {
        String currentStepName = currentStep.getTitel();
        for (String taskName : userGroupChanges.keySet()) {
            if (currentStepName.equals(taskName)) {
                // remove old usergroups
                List<Usergroup> currentGroups = currentStep.getBenutzergruppen();
                for (Usergroup oldGroup : currentGroups) {
                    StepManager.removeUsergroupFromStep(currentStep, oldGroup);
                }
                currentStep.getBenutzergruppen().clear();

                // add new user group assignments
                List<String> userGroupNames = userGroupChanges.get(taskName);
                for (String newGroupName : userGroupNames) {
                    Usergroup ug = UsergroupManager.getUsergroupByName(newGroupName);
                    if (ug != null) {
                        currentStep.getBenutzergruppen().add(ug);
                    }
                }
                // StepManager.saveStep(currentStep);
            }
        }
    }

    private void saveProcess(Process process, List<String> automaticRunSteps) throws DAOException {
        ProcessManager.saveProcess(process);

        for (Step currentStep : process.getSchritteList()) {
            String currentStepName = currentStep.getTitel();
            for (String autoRunStepName : automaticRunSteps) {
                if (currentStepName.equals(autoRunStepName)) {
                    ScriptThreadWithoutHibernate scriptThread = new ScriptThreadWithoutHibernate(currentStep);
                    scriptThread.startOrPutToQueue();
                }
            }
        }
    }

    @Override
    public boolean execute() {
        return run() == PluginReturnValue.FINISH;
    }

    @Override
    public String cancel() {
        return null;
    }

    @Override
    public String finish() {
        return null;
    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null;
    }

    @Override
    public int getInterfaceVersion() {
        return 1;
    }
}
