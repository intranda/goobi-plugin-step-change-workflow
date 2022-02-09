package de.intranda.goobi.plugins;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.lang.StringUtils;
import org.goobi.beans.LogEntry;
import org.goobi.beans.Process;
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
import de.sub.goobi.helper.ScriptThreadWithoutHibernate;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.enums.StepStatus;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.persistence.managers.ProcessManager;
import de.sub.goobi.persistence.managers.StepManager;
import de.sub.goobi.persistence.managers.UsergroupManager;
import lombok.Data;
import lombok.extern.log4j.Log4j;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DigitalDocument;
import ugh.dl.Fileformat;
import ugh.dl.Prefs;

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
            String realValue = null;

            // read the real value from the variable replacer
            DigitalDocument dd = null;
            try {
                Prefs prefs = process.getRegelsatz().getPreferences();
                Fileformat ff = process.readMetadataFile();
                if (ff == null) {
                    log.error("Metadata file is not readable for process with ID " + step.getProcessId());
                    LogEntry le = new LogEntry();
                    le.setProcessId(step.getProzess().getId());
                    le.setContent("Metadata file is not readable");
                    le.setType(LogType.ERROR);
                    le.setUserName("http step");
                    ProcessManager.saveLogEntry(le);
                    return PluginReturnValue.ERROR;
                }
                dd = ff.getDigitalDocument();
                VariableReplacer replacer = new VariableReplacer(dd, prefs, step.getProzess(), step);
                realValue = replacer.replace(variable);
                if (realValue.equals(variable)) {
                    realValue = null;
                }
            } catch (Exception e2) {
                log.error("An exception occurred while reading the metadata file for process with ID " + step.getProcessId(), e2);
                LogEntry le = new LogEntry();
                le.setProcessId(step.getProzess().getId());
                le.setContent("error reading metadata file");
                le.setType(LogType.ERROR);
                le.setUserName("http step");
                ProcessManager.saveLogEntry(le);
                return PluginReturnValue.ERROR;
            }

            String processTemplateName = configChanges.getString("workflow");
            List<String> stepsToOpen = Arrays.asList(configChanges.getStringArray("./steps[@type='open']/title"));
            List<String> stepToDeactivate = Arrays.asList(configChanges.getStringArray("./steps[@type='deactivate']/title"));
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

            // 1.) check if property name is set
            if (StringUtils.isBlank(variable)) {
                log.error("Cannot find property, abort");
                return PluginReturnValue.ERROR;
            }

            // 2.) check if property and value exist in process
            boolean conditionMatches = false;
            switch (condition) {
                case "missing":
                    if (realValue == null || realValue.trim().equals("")) {
                        conditionMatches = true;
                        anyConditionMatched = true;
                    }
                    break;

                case "available":
                    if (realValue != null && !realValue.trim().equals("")) {
                        conditionMatches = true;
                        anyConditionMatched = true;
                    }
                    break;

                case "is":
                    if (realValue != null && realValue.trim().equals(preferedValue)) {
                        conditionMatches = true;
                        anyConditionMatched = true;
                        break;
                    }
                    break;

                case "not":
                    if (realValue == null || !realValue.trim().equals(preferedValue)) {
                        conditionMatches = true;
                        anyConditionMatched = true;
                        break;
                    }
                    break;
            }

            if (conditionMatches) {
                automaticRunSteps.addAll(stepsToRunAutomatic);
            }

            // change process template
            if (conditionMatches && StringUtils.isNotBlank(processTemplateName)) {
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

            // 3.) run through tasks and change the status
            if (conditionMatches) {
                for (Step currentStep : process.getSchritteList()) {
                    for (String taskName : stepsToOpen) {
                        if (currentStep.getTitel().equals(taskName)) {
                            currentStep.setBearbeitungsstatusEnum(StepStatus.OPEN);
                        }
                    }
                    for (String taskName : stepToDeactivate) {
                        if (currentStep.getTitel().equals(taskName)) {
                            currentStep.setBearbeitungsstatusEnum(StepStatus.DEACTIVATED);
                        }
                    }

                    for (String taskName : stepsToLock) {
                        if (currentStep.getTitel().equals(taskName)) {
                            currentStep.setBearbeitungsstatusEnum(StepStatus.LOCKED);
                        }
                    }

                    for (String taskName : stepsToClose) {
                        if (currentStep.getTitel().equals(taskName)) {
                            currentStep.setBearbeitungsstatusEnum(StepStatus.DONE);
                        }
                    }

                    for (String taskName : userGroupChanges.keySet()) {
                        if (currentStep.getTitel().equals(taskName)) {

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
            }
        }

        // save the process if any change was done
        if (anyConditionMatched) {
            try {
                ProcessManager.saveProcess(process);
            } catch (DAOException e) {
                log.error(e);
                return PluginReturnValue.ERROR;
            }
            for (Step currentStep : process.getSchritteList()) {
                for (String autoRunStepName : automaticRunSteps) {
                    if (currentStep.getTitel().equals(autoRunStepName)) {
                        ScriptThreadWithoutHibernate scriptThread = new ScriptThreadWithoutHibernate(currentStep);
                        scriptThread.startOrPutToQueue();
                    }
                }
            }
        }

        return PluginReturnValue.FINISH;
    }

    @Override
    public boolean execute() {
        PluginReturnValue val = run();
        if (val == PluginReturnValue.FINISH) {
            return true;
        }
        return false;
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
