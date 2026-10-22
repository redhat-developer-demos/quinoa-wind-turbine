package org.acme.commentary;

import dev.langchain4j.model.openai.OpenAiChatModel;
import io.quarkiverse.langchain4j.ModelBuilderCustomizer;
import jakarta.enterprise.context.ApplicationScoped;

/** Keep provider retries inside the commentary deadline: one HTTP request per model invocation. */
@ApplicationScoped
public class CommentaryModelCustomizer implements ModelBuilderCustomizer<OpenAiChatModel.OpenAiChatModelBuilder> {
    @Override
    public void customize(OpenAiChatModel.OpenAiChatModelBuilder builder) {
        // Quarkiverse 1.14 requires its property to be >=1, but LangChain4j 1.20 counts retries after the first try.
        builder.maxRetries(0);
    }
}
