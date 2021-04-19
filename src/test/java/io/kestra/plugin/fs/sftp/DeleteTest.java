package io.kestra.plugin.fs.sftp;

import com.google.common.collect.ImmutableMap;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;

import javax.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

@MicronautTest
class DeleteTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private SftpUtils sftpUtils;

    @Test
    void exist() throws Exception {
        String from = "upload/" + IdUtils.create() + "/" + IdUtils.create() + ".yaml";

        sftpUtils.upload(from);

        Delete task = Delete.builder()
            .id(DeleteTest.class.getSimpleName())
            .type(Move.class.getName())
            .uri(from)
            .host("localhost")
            .port("6622")
            .username("foo")
            .password("pass")
            .build();

        Delete.Output run = task.run(TestsUtils.mockRunContext(runContextFactory, task, ImmutableMap.of()));

        assertThat(run.getUri().getPath(), containsString(from));
        assertThat(run.isDeleted(), is(true));
    }
}