package com.baek.viewer.config;

import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.spi.JobFactory;
import org.quartz.spi.TriggerFiredBundle;
import org.quartz.impl.StdSchedulerFactory;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;
import java.util.Properties;

/**
 * Quartz Scheduler 수동 구성 (spring-boot-starter-quartz 미사용).
 * org.quartz-scheduler:quartz:2.3.2 직접 사용.
 */
@Configuration
public class QuartzConfig {

    private Scheduler scheduler;

    /**
     * Spring DI를 지원하는 JobFactory.
     * Quartz가 Job 인스턴스를 생성할 때 Spring 빈 주입을 자동 수행.
     */
    @Bean
    public JobFactory springJobFactory(AutowireCapableBeanFactory beanFactory) {
        return new JobFactory() {
            @Override
            public org.quartz.Job newJob(TriggerFiredBundle bundle, Scheduler scheduler)
                    throws SchedulerException {
                try {
                    Class<? extends org.quartz.Job> jobClass = bundle.getJobDetail().getJobClass();
                    org.quartz.Job job = jobClass.getDeclaredConstructor().newInstance();
                    // Spring 의존성 주입
                    beanFactory.autowireBean(job);
                    return job;
                } catch (Exception e) {
                    throw new SchedulerException("Job 생성 실패: " + bundle.getJobDetail().getKey(), e);
                }
            }
        };
    }

    @Bean
    public Scheduler scheduler(JobFactory jobFactory) throws SchedulerException {
        Properties props = new Properties();
        props.put("org.quartz.scheduler.instanceName", "ApiViewerScheduler");
        props.put("org.quartz.threadPool.threadCount", "3");
        props.put("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");

        StdSchedulerFactory factory = new StdSchedulerFactory(props);
        scheduler = factory.getScheduler();
        scheduler.setJobFactory(jobFactory);
        scheduler.start();
        return scheduler;
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null) {
            try { scheduler.shutdown(true); } catch (Exception e) {}
        }
    }
}
