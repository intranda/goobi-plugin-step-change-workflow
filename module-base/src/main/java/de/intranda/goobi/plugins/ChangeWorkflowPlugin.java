package de.intranda.goobi.plugins;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.lang.StringUtils;
import org.goobi.beans.Process;
import org.goobi.beans.Processproperty;
import org.goobi.beans.Project;
import org.goobi.beans.Step;
import org.goobi.beans.Usergroup;
import org.goobi.production.enums.GoobiScriptResultType;
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
import de.sub.goobi.persistence.managers.MySQLHelper;
import de.sub.goobi.persistence.managers.ProcessManager;
import de.sub.goobi.persistence.managers.ProjectManager;
import de.sub.goobi.persistence.managers.PropertyManager;
import de.sub.goobi.persistence.managers.StepManager;
import de.sub.goobi.persistence.managers.UsergroupManager;
import lombok.Data;
import lombok.extern.log4j.Log4j;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.Prefs;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.UGHException;

@PluginImplementation
@Data
@Log4j
public class ChangeWorkflowPlugin implements IStepPluginVersion2 {

	private static final long serialVersionUID = 6166419150436281452L;

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
		boolean currentStepIsChanged = false;
		// run through all configured changes
		for (HierarchicalConfiguration configChanges : changes) {
			String conditionType = configChanges.getString("@type", "property"); // get it from config

			boolean conditionMatches = false;

			switch (conditionType) {
			case "property":
				conditionMatches = checkPropertyConditions(configChanges);
				break;
			case "checkDuplicates":
				// check if configured metadata exists in current process
				String metadataType = configChanges.getString("/metadata");

				try {
					String value = getMetadataValue(process, metadataType);
					if (StringUtils.isBlank(value)) {
						conditionMatches = false;
					} else {
						// check if other processes with configured metadata exist
						List<Integer> sharedIdList = getAllProcessesWithExactMetadata(metadataType, value);
						sharedIdList.remove(process.getId());
						// check if any of the other processes has already finished the current step
						if (sharedIdList.isEmpty()) {
							conditionMatches = false;
						} else {
							List<Integer> stepStatusList = getStepStatus(sharedIdList, step.getTitel());
							// if yes, change workflow
							if (stepStatusList.contains(3)) {
								conditionMatches = true;
							}
						}
					}
				} catch (SQLException e) {
					log.error(e);
				}
				break;
			default:
				break;
			}

			anyConditionMatched = anyConditionMatched || conditionMatches;

			// 3.) run through tasks and apply the changes
			if (conditionMatches) {
				// add new automatic steps
				prepareAutomaticSteps(configChanges, automaticRunSteps);

				// change process template
				processProcessTemplate(process, configChanges);

				// change project
				processProject(process, configChanges);

				// add log entries into the journal (process log)
				processLogs(process, configChanges);

				// run through tasks and change their status
				currentStepIsChanged = processStepsStatus(process, configChanges);

				// run through tasks and change their priorities
				processStepsPriority(process, configChanges);

				// change properties if configured
				properties(process, configChanges);
			}
		}

		// 4.) save the process if any change was done
		if (anyConditionMatched) {
			log.debug("anyConditionMatched = " + anyConditionMatched);
			try {
				saveProcess(process, automaticRunSteps);
			} catch (DAOException e) {
				log.error(e);
				return PluginReturnValue.ERROR;
			}
		}
		if (!currentStepIsChanged) {
			return PluginReturnValue.FINISH;
		} else {
			return PluginReturnValue.WAIT;
		}
	}

	private boolean checkPropertyConditions(HierarchicalConfiguration configChanges) {
		// 1.) check if property name is set and get its real value via VariableReplacer
		String variable = configChanges.getString("./propertyName");
		log.debug("propertyName = " + variable);
		if (StringUtils.isBlank(variable)) {
			log.error("Cannot find property, abort");
			return false;
		}

		String realValue = null;
		try {
			realValue = getRealValue(process, variable);

		} catch (Exception e2) {
			log.error(
					"An exception occurred while reading the metadata file for process with ID " + step.getProcessId(),
					e2);
			Helper.addMessageToProcessJournal(step.getProzess().getId(), LogType.ERROR, "error reading metadata file",
					"http step");
			return false;
		}
		log.debug("realValue = " + realValue);

		// 2.) check if property and value exist in process
		String preferedValue = configChanges.getString("./propertyValue", "");
		String condition = configChanges.getString("./propertyCondition", "is");

		log.debug("propertyValue = " + preferedValue);
		log.debug("propertyCondition = " + condition);

		boolean conditionMatches = checkCondition(condition, realValue, preferedValue);

		log.debug("conditionMatches = " + conditionMatches);

		return conditionMatches;
	}

	/**
	 * use VariableReplacer to get the real value of the given Goobi variable
	 * 
	 * @param process  the Goobi process, used to get the VariableReplacer object
	 * @param variable the Goobi variable that is to be replaced
	 * @return the value of the variable if it is a valid Goobi variable, null
	 *         otherwise
	 * @throws ReadException
	 * @throws IOException
	 * @throws SwapException
	 * @throws PreferencesException
	 */
	private String getRealValue(Process process, String variable)
			throws ReadException, IOException, SwapException, PreferencesException {
		Prefs prefs = process.getRegelsatz().getPreferences();
		Fileformat ff = process.readMetadataFile();
		if (ff == null) {
			log.error("Metadata file is not readable for process with ID " + step.getProcessId());
			Helper.addMessageToProcessJournal(step.getProzess().getId(), LogType.ERROR, "Metadata file is not readable",
					"http step");
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

	private String getMetadataValue(Process process, String variable) {
		String value = "";
		try {
			Fileformat ff = process.readMetadataFile();
			DigitalDocument dd = ff.getDigitalDocument();
			DocStruct logical = dd.getLogicalDocStruct();

			for (Metadata md : logical.getAllMetadata()) {
				if (md.getType().getName().equals(variable)) {
					return md.getValue();
				}
			}

		} catch (UGHException | IOException | SwapException e) {
			log.error(e);
		}

		return value;
	}

	/**
	 * check if the real value satisfies for the given condition
	 * 
	 * @param condition     options are "missing" "available" "is" and "not", any
	 *                      other inputs would just trigger a false to return
	 * @param realValue
	 * @param preferedValue used for conditions "is" and "not"
	 * @return true if the real value input satisfies for the given condition, false
	 *         otherwise
	 */
	private boolean checkCondition(String condition, String realValue, String preferedValue) {
		log.debug("checking condition: " + condition);
		switch (condition) {
		case "missing":
			return realValue == null || "".equals(realValue.trim());

		case "available":
			return realValue != null && !"".equals(realValue.trim());

		case "is":
			return realValue != null && realValue.trim().equals(preferedValue);

		case "not":
			return realValue == null || !realValue.trim().equals(preferedValue);

		default:
			return false;
		}
	}

	/**
	 * get newly configured automatic steps and add it into the input list
	 * 
	 * @param configChanges     used to get the newly configured automatic steps
	 * @param automaticRunSteps list for keeping all automatic steps
	 */
	private void prepareAutomaticSteps(HierarchicalConfiguration configChanges, List<String> automaticRunSteps) {
		List<String> stepsToRunAutomatic = getStepsGivenStatus(configChanges, "run");
		automaticRunSteps.addAll(stepsToRunAutomatic);
	}

	/**
	 * process changes regarding process templates
	 * 
	 * @param process       the Goobi process
	 * @param configChanges used to get the newly configured process template name
	 */
	private void processProcessTemplate(Process process, HierarchicalConfiguration configChanges) {
		String processTemplateName = configChanges.getString("./workflow");
		log.debug("processTemplateName = " + processTemplateName);

		if (StringUtils.isNotBlank(processTemplateName)) {
			changeProcessTemplate(process, processTemplateName);
		}
	}

	/**
	 * apply changes regarding process templates
	 * 
	 * @param process             the Goobi process
	 * @param processTemplateName name of the new process template
	 */
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

	/**
	 * process changes regarding projects
	 * 
	 * @param process       the Goobi process
	 * @param configChanges used to get the newly configured process project name
	 */
	private void processProject(Process process, HierarchicalConfiguration configChanges) {
		String projectName = configChanges.getString("./project");
		log.debug("projectName = " + projectName);

		if (StringUtils.isNotBlank(projectName)) {
			changeProject(process, projectName);
		}
	}

	/**
	 * apply changes regarding projects
	 * 
	 * @param process     the Goobi process
	 * @param projectName name of the new project
	 */
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

	/**
	 * property changes
	 * 
	 * @param process       the Goobi process
	 * @param configChanges used to get the configured properties to be changed
	 */
	private void properties(Process process, HierarchicalConfiguration configChanges) {
		log.debug("processing properties");

		List<HierarchicalConfiguration> props = configChanges.configurationsAt("./properties/property");
		for (HierarchicalConfiguration prop : props) {
			String name = prop.getString("@name");
			String value = prop.getString("@value");
			boolean delete = prop.getBoolean("@delete", false);

			// if property shall be deleted
			if (delete) {
				for (Processproperty pp : process.getEigenschaften()) {
					if (pp.getTitel().equals(name)) {
						PropertyManager.deleteProcessProperty(pp);
						break;
					}
				}
			} else {
				// if property shall get a new value
				boolean matched = false;
				for (Processproperty pp : process.getEigenschaften()) {
					if (pp.getTitel().equals(name)) {
						pp.setWert(value);
						pp.setProcessId(process.getId());
						PropertyManager.saveProcessProperty(pp);
						matched = true;						
						break;
					}
				}
				 if (!matched) {
			            Processproperty pp = new Processproperty();
			            pp.setTitel(name);
			            pp.setWert(value);
			            pp.setProzess(process);
			            PropertyManager.saveProcessProperty(pp);
			     }
			}
		}
	}

	/**
	 * process changes regarding logs
	 * 
	 * @param process       the Goobi process
	 * @param configChanges used to get the newly configured log types
	 */
	private void processLogs(Process process, HierarchicalConfiguration configChanges) {
		log.debug("processing logs");
		List<String> logError = getLogsGivenType(configChanges, "error");
		List<String> logInfo = getLogsGivenType(configChanges, "info");
		List<String> logUser = getLogsGivenType(configChanges, "user");
		List<String> logDebug = getLogsGivenType(configChanges, "debug");

		List<List<String>> logLists = Arrays.asList(logError, logInfo, logUser, logDebug);
		LogType[] logTypeValues = new LogType[] { LogType.ERROR, LogType.INFO, LogType.USER, LogType.DEBUG };
		addAllLogEntries(process, logLists, logTypeValues);
	}

	/**
	 * process changes regarding steps' status
	 * 
	 * @param process       the Goobi process
	 * @param configChanges used to get the newly configured steps with their status
	 *                      values
	 */
	private boolean processStepsStatus(Process process, HierarchicalConfiguration configChanges) {
		log.debug("processing steps' status");
		List<String> stepsToOpen = getStepsGivenStatus(configChanges, "open");
		List<String> stepsToDeactivate = getStepsGivenStatus(configChanges, "deactivate");
		List<String> stepsToClose = getStepsGivenStatus(configChanges, "close");
		List<String> stepsToLock = getStepsGivenStatus(configChanges, "lock");

		Map<String, List<String>> userGroupChanges = new HashMap<>();
		List<HierarchicalConfiguration> userGroupDefinition = configChanges.configurationsAt("./usergroups");
		for (HierarchicalConfiguration def : userGroupDefinition) {
			String stepTitle = def.getString("@step");
			List<String> groups = Arrays.asList(def.getStringArray("usergroup"));
			userGroupChanges.put(stepTitle, groups);
		}

		List<List<String>> stepsTypeLists = Arrays.asList(stepsToOpen, stepsToDeactivate, stepsToClose, stepsToLock);
		StepStatus[] statusValues = new StepStatus[] { StepStatus.OPEN, StepStatus.DEACTIVATED, StepStatus.DONE,
				StepStatus.LOCKED };
		changeAllStatus(process, stepsTypeLists, statusValues, userGroupChanges);

		return stepsToOpen.contains(step.getTitel()) || stepsToDeactivate.contains(step.getTitel())
				|| stepsToClose.contains(step.getTitel()) || stepsToLock.contains(step.getTitel());
	}

	/**
	 * process changes regarding steps' priorities
	 * 
	 * @param process       the Goobi process
	 * @param configChanges used to get the newly configured steps with their
	 *                      priorities
	 */
	private void processStepsPriority(Process process, HierarchicalConfiguration configChanges) {
		log.debug("processing steps' priority");
		List<String> stepsWithPriorityStandard = getStepsGivenPriority(configChanges, "0");
		List<String> stepsWithPriorityHigh = getStepsGivenPriority(configChanges, "1");
		List<String> stepsWithPriorityHigher = getStepsGivenPriority(configChanges, "2");
		List<String> stepsWithPriorityHighest = getStepsGivenPriority(configChanges, "3");
		List<String> stepsWithPriorityCorrection = getStepsGivenPriority(configChanges, "10");

		List<List<String>> stepsPriorityLists = Arrays.asList(stepsWithPriorityStandard, stepsWithPriorityHigh,
				stepsWithPriorityHigher, stepsWithPriorityHighest, stepsWithPriorityCorrection);
		int[] priorityValues = new int[] { 0, 1, 2, 3, 10 };

		// check if there is any * in the configured steps
		for (int i = 0; i < priorityValues.length; ++i) {
			for (String s : stepsPriorityLists.get(i)) {
				if ("*".equals(s)) {
					changeAllPriorities(process, priorityValues[i]);
					return;
				}
			}
		}
		// no * found
		changeAllPriorities(process, stepsPriorityLists, priorityValues);
	}

	/**
	 * get the list of steps given the input status value
	 * 
	 * @param configChanges used to get the list of steps
	 * @param statusValue
	 * @return the list of names of the configured steps whose configured status
	 *         values equal the input
	 */
	private List<String> getStepsGivenStatus(HierarchicalConfiguration configChanges, String statusValue) {
		String tagName = "steps";
		String attributeName = "type";
		String option = "title";
		return getChangesWithProperty(configChanges, tagName, attributeName, statusValue, option);
	}

	/**
	 * get the list of steps given the input priority value
	 * 
	 * @param configChanges used to get the list of steps
	 * @param priority
	 * @return the list of names of the configured steps whose configured priority
	 *         values equal the input
	 */
	private List<String> getStepsGivenPriority(HierarchicalConfiguration configChanges, String priority) {
		String tagName = "priority";
		String attributeName = "value";
		String option = "title";
		return getChangesWithProperty(configChanges, tagName, attributeName, priority, option);
	}

	/**
	 * get the list of logs given the input log type
	 * 
	 * @param configChanges used to get the list of logs
	 * @param typeValue
	 * @return the list of the configured logs whose configured type values equal
	 *         the input
	 */
	private List<String> getLogsGivenType(HierarchicalConfiguration configChanges, String typeValue) {
		String tagName = "log";
		String attributeName = "type";
		String option = "";
		return getChangesWithProperty(configChanges, tagName, attributeName, typeValue, option);
	}

	/**
	 * get the list of items in a <change></change> block given a tag name, an
	 * attribute name, an attribute value and an option
	 * 
	 * @param configChanges  used to get the list
	 * @param tagName
	 * @param attributeName
	 * @param attributeValue
	 * @param option         name of a child tag under tagName, default ""
	 * @return the list of items that match
	 */
	private List<String> getChangesWithProperty(HierarchicalConfiguration configChanges, String tagName,
			String attributeName, String attributeValue, String option) {
		String changePath = "./" + tagName + "[@" + attributeName + "='" + attributeValue + "']"
				+ (StringUtils.isBlank(option) ? "" : "/" + option);
		return Arrays.asList(configChanges.getStringArray(changePath));
	}

	/**
	 * add all newly configured log types into the process
	 * 
	 * @param process       the Goobi process
	 * @param logLists      lists of configured logs of different types
	 * @param logTypeValues the list of log types of elements of logLists
	 */
	private void addAllLogEntries(Process process, List<List<String>> logLists, LogType[] logTypeValues) {
		if (logLists.size() != logTypeValues.length) {
			// error here since these two must match
			log.error("The sizes of the input list and array do not match!");
			return;
		}
		for (int i = 0; i < logLists.size(); ++i) {
			addLogEntries(process, logLists.get(i), logTypeValues[i]);
		}
	}

	/**
	 * add a list of logs of one given type into the process
	 * 
	 * @param process the Goobi process
	 * @param logList list of configured logs of type logType
	 * @param logType type of elements of logList
	 */
	private void addLogEntries(Process process, List<String> logList, LogType logType) {
		for (String s : logList) {
			Helper.addMessageToProcessJournal(process.getId(), logType, s);
		}
	}

	/**
	 * change all status values of the steps of the process
	 * 
	 * @param process          the Goobi process
	 * @param stepsLists       lists of steps with different status values
	 * @param statusValues     the list of status values for the lists in stepsLists
	 * @param userGroupChanges the list of changes upon user group
	 */
	private void changeAllStatus(Process process, List<List<String>> stepsLists, StepStatus[] statusValues,
			Map<String, List<String>> userGroupChanges) {
		if (stepsLists.size() != statusValues.length) {
			// error here since these two must match
			log.error("The sizes of the input list and array do not match!");
			return;
		}
		for (Step currentStep : process.getSchritteList()) {
			// change step status
			for (int i = 0; i < stepsLists.size(); ++i) {
				changeStatus(currentStep, stepsLists.get(i), statusValues[i]);
			}

			// change user groups
			changeUserGroups(currentStep, userGroupChanges);
		}
	}

	/**
	 * change the status value of the input step
	 * 
	 * @param currentStep the Step whose status value is to be changed
	 * @param stepsList   the list of steps whose status values are to be changed to
	 *                    status
	 * @param status      the status value that all steps in stepsList should be
	 *                    changed to
	 */
	private void changeStatus(Step currentStep, List<String> stepsList, StepStatus status) {
		String currentStepName = currentStep.getTitel();
		for (String taskName : stepsList) {
			if (currentStepName.equals(taskName)) {
				currentStep.setBearbeitungsstatusEnum(status);
			}
		}
	}

	/**
	 * change the user group of the input step
	 * 
	 * @param currentStep      the step whose user group is to be changed
	 * @param userGroupChanges a map containing all changes regarding user groups
	 */
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
			}
		}
	}

	/**
	 * change all steps of the process to the same priority value
	 * 
	 * @param process  the Goobi process
	 * @param priority the priority value for all the steps in this process
	 */
	private void changeAllPriorities(Process process, int priority) {
		for (Step s : process.getSchritteList()) {
			s.setPrioritaet(priority);
		}
	}

	/**
	 * change all priority values of the steps of the process
	 * 
	 * @param process        the Goobi process
	 * @param stepsLists     lists of steps with different priority values
	 * @param priorityValues the list of priority values for the lists in stepsLists
	 */
	private void changeAllPriorities(Process process, List<List<String>> stepsLists, int[] priorityValues) {
		if (stepsLists.size() != priorityValues.length) {
			// error here since these two must match
			log.error("The sizes of the input list and array do not match!");
			return;
		}
		for (Step currentStep : process.getSchritteList()) {
			for (int i = 0; i < stepsLists.size(); ++i) {
				changePriority(currentStep, stepsLists.get(i), priorityValues[i]);
			}
		}
	}

	/**
	 * change the priority value of the input step
	 * 
	 * @param currentStep the Step whose priority value is to be changed
	 * @param stepsList   the list of steps whose priority values are to be changed
	 *                    to priority
	 * @param priority    the priority value that all steps in stepsList should be
	 *                    changed to
	 */
	private void changePriority(Step currentStep, List<String> stepsList, int priority) {
		String currentStepName = currentStep.getTitel();
		for (String taskName : stepsList) {
			if (currentStepName.equals(taskName)) {
				currentStep.setPrioritaet(priority);
			}
		}
	}

	/**
	 * save the process and apply changes on automatic steps
	 * 
	 * @param process           the Goobi process
	 * @param automaticRunSteps the list of automatic steps
	 * @throws DAOException
	 */
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
		return null; // NOSONAR
	}

	@Override
	public int getInterfaceVersion() {
		return 1;
	}

	private static List<Integer> getAllProcessesWithExactMetadata(String name, String value) throws SQLException {
		String sql = "SELECT processid FROM metadata WHERE name = ? and value = ?";
		Connection connection = null;
		try {
			connection = MySQLHelper.getInstance().getConnection();
			return new QueryRunner().query(connection, sql, MySQLHelper.resultSetToIntegerListHandler, name, value);
		} finally {
			if (connection != null) {
				MySQLHelper.closeConnection(connection);
			}
		}
	}

	private static List<Integer> getStepStatus(List<Integer> processids, String stepName) throws SQLException {
		StringBuilder sb = new StringBuilder();
		for (Integer id : processids) {
			if (sb.length() > 0) {
				sb.append(", ");
			}
			sb.append(id);
		}

		String sql = "select distinct Bearbeitungsstatus from schritte where prozesseid in (" + sb.toString()
				+ ") and titel = ?";
		Connection connection = null;
		try {
			connection = MySQLHelper.getInstance().getConnection();
			return new QueryRunner().query(connection, sql, MySQLHelper.resultSetToIntegerListHandler, stepName);
		} finally {
			if (connection != null) {
				MySQLHelper.closeConnection(connection);
			}
		}
	}

}
