package hydra.intranet.swarmManager.service;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Service;
import com.github.dockerjava.api.model.SwarmNode;
import com.github.dockerjava.api.model.Task;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;
import com.google.common.base.Optional;

import hydra.intranet.swarmManager.domain.Ecosystem;
import hydra.intranet.swarmManager.domain.Pool;
import hydra.intranet.swarmManager.event.EcosystemRemoved;
import hydra.intranet.swarmManager.service.detector.IDetector;
import hydra.intranet.swarmManager.service.task.SwarmCollectTask;
import hydra.intranet.swarmManager.service.validator.IEcosystemValidator;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SwarmService {

	private static final String DOCKER_STACK_LABEL = "com.docker.stack.namespace";

	private DockerClient client;

	@Autowired
	private Collection<IEcosystemValidator> ecosystemValidators;

	@Autowired
	private Collection<IDetector> detectors;

	private Collection<Ecosystem> ecosystems = new ArrayList<>();

	@Autowired
	private ConfigService configService;

	@Autowired
	private PoolService poolService;

	@Autowired
	private ThreadPoolTaskScheduler taskScheduler;

	@Autowired
	private ApplicationEventPublisher applicationEventPublisher;

	@EventListener(ApplicationReadyEvent.class)
	public void doSomethingAfterStartup() {
		final DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().withDockerHost(configService.getString("DOCKER_HOST")).build();
		client = DockerClientBuilder.getInstance(config).build();

		final Trigger nextTrigger = triggerContext -> {
			final Calendar nextExecutionTime = new GregorianCalendar();
			final Date lastActualExecutionTime = triggerContext.lastActualExecutionTime();
			nextExecutionTime.setTime(lastActualExecutionTime != null ? lastActualExecutionTime : new Date());
			nextExecutionTime.add(Calendar.SECOND, Math.toIntExact(configService.getLong("CHECK_FIXED_DELAY_IN_SEC")));
			return nextExecutionTime.getTime();
		};
		taskScheduler.schedule(new SwarmCollectTask(this), nextTrigger);
	}

	public Collection<Ecosystem> getEcosystems() {
		return ecosystems;
	}

	public Collection<Ecosystem> getEcosystems(final String poolId) {
		final Collection<Ecosystem> ecosystems = new ArrayList<>();
		final Optional<Pool> maybePool = poolService.getPool(poolId);
		if (maybePool.isPresent()) {
			ecosystems.addAll(getEcosystems(maybePool.get()));
		}
		return ecosystems;
	}

	public Collection<Ecosystem> getEcosystems(final Pool pool) {
		return getEcosystems().stream().filter(e -> e.getPools().equals(pool)).collect(Collectors.toList());
	}

	public Collection<Ecosystem> collectEcosystems() {
		final long start = System.currentTimeMillis();
		final Collection<Ecosystem> ecosystems = collectRawEcosystems();

		ecosystems.forEach(eco -> {
			ecosystemValidators.forEach(calc -> {
				calc.calculate(eco);
			});
		});

		detectors.forEach(d -> {
			d.detect(ecosystems);
		});

		log.info("Ecosystem collected in {} millisec", (System.currentTimeMillis() - start));

		this.ecosystems = ecosystems;
		return ecosystems;
	}

	public Collection<SwarmNode> getNodes() {
		return client.listSwarmNodesCmd().exec();
	}

	public String getJoinToken() {
		return client.joinSwarmCmd().getJoinToken();
	}

	public void removeEcosystem(final Ecosystem ecosystem) {
		if (configService.isTrue("EXEC_REMOVE_COMMAND")) {
			final String rmCommand = ecosystem.isStack() ? "docker stack rm " + ecosystem.getName() : "docker service rm " + ecosystem.getName();
			try {
				log.info("Remove ecosystem: {}", ecosystem.getName());
				Runtime.getRuntime().exec(rmCommand);
				applicationEventPublisher.publishEvent(new EcosystemRemoved(this, ecosystem));
			} catch (final Exception e) {
				log.error("Error in remove command", e);
			}
		}
	}

	private Collection<Ecosystem> collectRawEcosystems() {
		final Collection<Ecosystem> ecosystems = new ArrayList<>();
		final List<Service> rawServices = client.listServicesCmd().exec();
		rawServices.forEach(service -> {
			final Optional<Map<String, String>> maybeLabels = Optional.fromNullable(service.getSpec().getLabels());
			final boolean isStack = maybeLabels.isPresent() && maybeLabels.get().containsKey(DOCKER_STACK_LABEL);
			final String name = isStack ? maybeLabels.get().get(DOCKER_STACK_LABEL) : service.getSpec().getName();
			final List<Task> tasks = client.listTasksCmd().withServiceFilter(service.getSpec().getName()).exec();
			if (isStack) {
				addTaskToEcosystem(ecosystems, name, tasks, maybeLabels);
			} else {
				final Ecosystem eco = Ecosystem.builder().isStack(isStack).name(name).build();
				eco.addLabel(maybeLabels);
				eco.addTasks(tasks);
				ecosystems.add(eco);
			}
		});
		return ecosystems;
	}

	private void addTaskToEcosystem(final Collection<Ecosystem> ecosystems, final String stackName, final List<Task> tasks, final Optional<Map<String, String>> maybeLabels) {
		final List<Ecosystem> ecosystemArray = ecosystems.stream().filter(s -> s.getName().equals(stackName)).collect(Collectors.toList());
		if (CollectionUtils.isEmpty(ecosystemArray)) { // Create new stack
			final Ecosystem eco = Ecosystem.builder().isStack(true).name(stackName).build();
			eco.addLabel(maybeLabels);
			eco.addTasks(tasks);
			ecosystems.add(eco);
		} else { // Add to existing stack
			ecosystemArray.forEach(e -> {
				e.addLabel(maybeLabels);
				e.addTasks(tasks);
			});
		}
	}

}
