package com.mhackner.bamboo;

import com.atlassian.bamboo.chains.Chain;
import com.atlassian.bamboo.chains.ChainExecution;
import com.atlassian.bamboo.plugins.git.GitHubRepository;
import com.atlassian.bamboo.plugins.git.GitRepository;
import com.atlassian.bamboo.repository.RepositoryDefinition;
import com.atlassian.bamboo.security.EncryptionService;
import com.atlassian.bamboo.util.Narrow;
import com.atlassian.bamboo.variable.CustomVariableContext;
import com.atlassian.bamboo.variable.VariableDefinitionContext;
import com.atlassian.sal.api.ApplicationProperties;
import org.apache.commons.lang.StringUtils;
import org.eclipse.egit.github.core.CommitStatus;
import org.eclipse.egit.github.core.RepositoryId;
import org.eclipse.egit.github.core.client.GitHubClient;
import org.eclipse.egit.github.core.service.CommitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

public abstract class AbstractGitHubStatusAction {

    private static final Logger log = LoggerFactory.getLogger(AbstractGitHubStatusAction.class);

    private final ApplicationProperties applicationProperties;
    private final EncryptionService encryptionService;
    private final CustomVariableContext ctx;

    public AbstractGitHubStatusAction(ApplicationProperties applicationProperties,
                                      EncryptionService encryptionService, CustomVariableContext ctx) {
        this.applicationProperties = applicationProperties;
        this.encryptionService = encryptionService;
        this.ctx = ctx;
    }

    void updateStatus(String status, Chain chain, ChainExecution chainExecution) {

        String disabled = chain.getBuildDefinition().getCustomConfiguration()
                .get("custom.gitHubStatus.disabled");

        VariableDefinitionContext cvc = ctx.getVariableContexts().get("github.status_access_key");


        if (Boolean.parseBoolean(disabled) || cvc == null) {
            return;
        }

        String oauth = cvc.getValue();

        List<RepositoryDefinition> repos = chain.getEffectiveRepositoryDefinitions();
        if (repos.size() != 1) {
            log.warn("Wanted 1 repo but found {}. Not updating GitHub status.", repos.size());
            return;
        }

        RepositoryDefinition repoDefinition = repos.get(0);
        String sha = chainExecution.getBuildChanges().getVcsRevisionKey(repoDefinition.getId());

        @SuppressWarnings("deprecation")
        String url = String.format("%s/browse/%s",
                StringUtils.removeEnd(applicationProperties.getBaseUrl(), "/"),
                chainExecution.getPlanResultKey());

        GitHubRepository github = Narrow.downTo(repoDefinition.getRepository(), GitHubRepository.class);
        if (github != null) {
            setStatusForGithub(github, status, sha, url);
        } else {
            setStatusForGit(Narrow.downTo(repoDefinition.getRepository(), GitRepository.class), status, sha, url, oauth);
        }
    }

    private void setStatusForGithub(GitHubRepository github, String status, String sha, String url) {
        GitHubClient client = new GitHubClient().setCredentials(github.getUsername(), encryptionService.decrypt(github.getEncryptedPassword()));
        CommitService commitService = new CommitService(client);
        CommitStatus commitStatus = new CommitStatus().setState(status).setTargetUrl(url);
        try {
            commitService.createStatus(RepositoryId.createFromId(github.getRepository()), sha, commitStatus);
            log.info("GitHub status for commit {} set to {}.", sha, status);
        } catch (IOException ex) {
            log.error("Failed to update GitHub status", ex);
        }
    }

    private static String normalizeUrl(String url) {
        return (StringUtils.endsWithIgnoreCase(url, ".git")) ? StringUtils.substring(url, 0, url.length() - 4) : url;
    }

    private void setStatusForGit(GitRepository repo, String status, String sha, String url, String oauth) {
        if (repo == null) return;

        GitHubClient client = new GitHubClient().setOAuth2Token(oauth);
        CommitService commitService = new CommitService(client);
        CommitStatus commitStatus = new CommitStatus().setState(status).setTargetUrl(url);
        try {
            commitService.createStatus(RepositoryId.createFromUrl(normalizeUrl(repo.getAccessData().getRepositoryUrl())), sha, commitStatus);
            log.info("GitHub status for commit {} set to {}.", sha, status);
        } catch (IOException ex) {
            log.error("Failed to update GitHub status", ex);
        }
    }

}
