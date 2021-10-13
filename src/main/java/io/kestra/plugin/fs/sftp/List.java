package io.kestra.plugin.fs.sftp;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.fs.sftp.models.File;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.apache.commons.vfs2.provider.sftp.SftpFileObject;
import org.slf4j.Logger;

import java.net.URI;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.kestra.core.utils.Rethrow.throwFunction;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "List files in a sftp server directory"
)
@Plugin(
    examples = {
        @Example(
            code = {
                "type: io.kestra.plugin.fs.sftp.List",
                "from: \"/upload/dir1/\"",
                "regExp: \".*\\/dir1\\/.*\\.(yaml|yml)\"",
            }
        )
    }
)
public class List extends AbstractSftpTask implements RunnableTask<List.Output> {
    @Schema(
        title = "The fully-qualified URIs that point to path"
    )
    @PluginProperty(dynamic = true)
    protected String from;

    @Schema(
        title = "A regexp to filter on full path"
    )
    @PluginProperty(dynamic = true)
    private String regExp;

    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        try (StandardFileSystemManager fsm = new StandardFileSystemManager()) {
            fsm.init();

            // path
            URI from = this.sftpUri(runContext, this.from);
            String regExp = runContext.render(this.regExp);

            // connection options
            FileSystemOptions fileSystemOptions = this.fsOptions(runContext);

            // list
            try (
                FileObject local = fsm.resolveFile(from.toString(), fileSystemOptions)
            ) {
                FileObject[] children = local.getChildren();

                java.util.List<File> list = Stream.of(children)
                    .map(throwFunction(r -> File.of((SftpFileObject) r)))
                    .filter(r -> regExp == null || r.getPath().toString().matches(regExp))
                    .collect(Collectors.toList());

                logger.debug("Found '{}' files from '{}'", list.size(), from);

                return Output.builder()
                    .files(list)
                    .build();
            }
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The list of files"
        )
        private final java.util.List<File> files;
    }
}
