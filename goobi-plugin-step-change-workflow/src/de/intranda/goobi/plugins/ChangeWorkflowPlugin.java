package de.intranda.goobi.plugins;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.lang.StringUtils;
import org.goobi.beans.Process;
import org.goobi.beans.Processproperty;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.enums.StepStatus;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.persistence.managers.ProcessManager;
import lombok.Data;
import lombok.extern.log4j.Log4j;
import net.xeoh.plugins.base.annotations.PluginImplementation;

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

    private String propertyName;
    private String propertyValue;
    private List<String> stepsToOpen = new ArrayList<>();
    private List<String> stepToDeactivate = new ArrayList<>();
    private List<String> stepsToClose = new ArrayList<>();
    private List<String> stepsToLock = new ArrayList<>();

    @SuppressWarnings("unchecked")
    @Override
    public void initialize(Step step, String returnPath) {
        this.step = step;
        this.process = step.getProzess();
        this.pagePath = returnPath;

        String projectName = step.getProzess().getProjekt().getTitel();

        XMLConfiguration xmlConfig = ConfigPlugins.getPluginConfig(title);
        xmlConfig.setExpressionEngine(new XPathExpressionEngine());
        xmlConfig.setReloadingStrategy(new FileChangedReloadingStrategy());

        SubnodeConfiguration config = null;

        // order of configuration is:
        //        1.) project name and step name matches
        //        2.) step name matches and project is *
        //        3.) project name matches and step name is *
        //        4.) project name and step name are *
        try {
            config = xmlConfig.configurationAt("//config[./project = '" + projectName + "'][./step = '" + step.getTitel() + "']");
        } catch (IllegalArgumentException e) {
            try {
                config = xmlConfig.configurationAt("//config[./project = '*'][./step = '" + step.getTitel() + "']");
            } catch (IllegalArgumentException e1) {
                try {
                    config = xmlConfig.configurationAt("//config[./project = '" + projectName + "'][./step = '*']");
                } catch (IllegalArgumentException e2) {
                    config = xmlConfig.configurationAt("//config[./project = '*'][./step = '*']");
                }
            }
        }
        propertyName = config.getString("./propertyName");
        propertyValue = config.getString("./propertyValue");

        stepsToOpen = config.getList("./steps[@type='open']/title");
        stepToDeactivate = config.getList("./steps[@type='deactivate']/title");
        stepsToClose = config.getList("./steps[@type='close']/title");
        stepsToLock = config.getList("./steps[@type='lock']/title");
    }

    @Override
    public PluginReturnValue run() {
        //        1.) check if property and value are set

        if (StringUtils.isBlank(propertyName) || StringUtils.isBlank(propertyValue)) {
            log.error("Cannot find property name or value to check, abort");
            return PluginReturnValue.ERROR;
        }

        //        2.) check if property and value exist in process
        boolean conditionMatches = false;
        for (Processproperty property : process.getEigenschaften()) {
            if (property.getTitel().equals(propertyName) && property.getWert().equals(propertyValue)) {
                conditionMatches = true;
                break;
            }
        }

        //        3.) run through tasks and change the status
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

            }
            try {
                ProcessManager.saveProcess(process);
            } catch (DAOException e) {
                log.error(e);
                return PluginReturnValue.ERROR;
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
