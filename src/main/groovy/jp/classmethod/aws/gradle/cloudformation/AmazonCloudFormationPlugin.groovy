package jp.classmethod.aws.gradle.cloudformation

import java.util.List;

import groovy.lang.Closure
import groovy.lang.Lazy;
import jp.classmethod.aws.gradle.AwsPlugin
import jp.classmethod.aws.gradle.AwsPluginExtension;
import jp.classmethod.aws.gradle.s3.*

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task

import com.amazonaws.*
import com.amazonaws.auth.*
import com.amazonaws.auth.policy.actions.CloudFormationActions;
import com.amazonaws.regions.*
import com.amazonaws.services.s3.*
import com.amazonaws.services.s3.model.*
import com.amazonaws.services.cloudformation.*
import com.amazonaws.services.cloudformation.model.*

/**
 * A plugin which configures a AWS project.
 */
class AmazonCloudFormationPlugin implements Plugin<Project> {
	
	void apply(Project project) {
		project.configure(project) {
			apply plugin: 'aws'
			project.extensions.create(AwsCloudFormationPluginExtension.NAME, AwsCloudFormationPluginExtension, project)
			applyTasks(project)
		}
	}
	
	void applyTasks(final Project project) {
		def awsCfnUploadTemplate = project.task('awsCfnUploadTemplate', type: AmazonS3FileUploadTask) { AmazonS3FileUploadTask t ->
			t.description = 'Upload cfn template file to the Amazon S3 bucket.'
			t.doFirst {
				t.file =  project.cloudFormation.templateFile
				
				def String extension = t.file.name.tokenize('.').last()
				def String filename  = t.file.name.tokenize('/').last()
				def String baseName  = filename.substring(0, filename.length() - extension.length() - 1)
				def String timestamp = new Date().format("yyyyMMdd'_'HHmmss", TimeZone.default)
				
				t.bucketName = project.cloudFormation.templateBucket
				t.key = "${project.cloudFormation.templateKeyPrefix}/${baseName}-${project.version}-${timestamp}.${extension}"
			}
			t.doLast {
				project.cloudFormation.templateURL = t.resourceUrl
			}
		}
		
		def awsCfnMigrateStack = project.task('awsCfnMigrateStack', type: AmazonCloudFormationMigrateStackTask) { AmazonCloudFormationMigrateStackTask t ->
			t.description 'Create/Migrate cfn stack'
			t.mustRunAfter awsCfnUploadTemplate
			t.doFirst {
				t.stackName = project.cloudFormation.stackName
				t.capabilityIam = project.cloudFormation.capabilityIam
				project.cloudFormation.stackParams.each {
					t.cfnStackParams += new com.amazonaws.services.cloudformation.model.Parameter()
						.withParameterKey(it.key).withParameterValue((String) it.value)
				}
				t.cfnTemplateUrl = project.cloudFormation.templateURL
			}
		}
		
		project.task('awsCfnWaitStackReady', type: AmazonCloudFormationWaitStackStatusTask) { AmazonCloudFormationWaitStackStatusTask t ->
			t.description 'Wait cfn stack for *_COMPLETE status.'
			t.mustRunAfter awsCfnMigrateStack
			t.doFirst {
				t.stackName = project.cloudFormation.stackName
			}
		}
		
		def awsCfnWaitStackComplete = project.task('awsCfnWaitStackComplete', type: AmazonCloudFormationWaitStackStatusTask) { AmazonCloudFormationWaitStackStatusTask t ->
			t.description 'Wait cfn stack for CREATE_COMPETE or UPDATE_COMPLETE status.'
			t.mustRunAfter awsCfnMigrateStack
			t.successStatuses = [ 'CREATE_COMPLETE', 'UPDATE_COMPLETE' ]
			t.doFirst {
				t.stackName = project.cloudFormation.stackName
			}
		}
		
		project.task('awsCfnMigrateStackAndWaitCompleted') { Task t ->
			t.description = 'Create/Migrate cfn stack, and wait stack for CREATE_COMPETE or UPDATE_COMPLETE status.'
			t.dependsOn([awsCfnMigrateStack, awsCfnWaitStackComplete])
		}
		
		def awsCfnDeleteStack = project.task('awsCfnDeleteStack', type: AmazonCloudFormationDeleteStackTask) { AmazonCloudFormationDeleteStackTask t ->
			t.description 'Delete cfn stack'
			t.doFirst {
				t.stackName = project.cloudFormation.stackName
			}
		}
		
		def awsCfnWaitStackDeleted = project.task('awsCfnWaitStackDeleted', type: AmazonCloudFormationWaitStackStatusTask) { AmazonCloudFormationWaitStackStatusTask t ->
			t.description 'Wait cfn stack for DELETE_COMPLETE status.'
			t.mustRunAfter awsCfnDeleteStack
			t.successStatuses = [ 'DELETE_COMPLETE' ]
			t.doFirst {
				t.stackName = project.cloudFormation.stackName
			}
		}
		
		project.task('awsCfnDeleteStackAndWaitCompleted') { Task t ->
			t.description 'Delete cfn stack, and wait stack for DELETE_COMPLETE status.'
			t.dependsOn awsCfnDeleteStack, awsCfnWaitStackDeleted
		}
	}
}

class AwsCloudFormationPluginExtension {
	
	public static final NAME = 'cloudFormation'
	
	Project project;
	
	@Lazy
	AmazonCloudFormation cfn = {
		AwsPluginExtension aws = project.extensions.getByType(AwsPluginExtension)
		aws.configureRegion(new AmazonCloudFormationClient(aws.credentialsProvider))
	}()
	
	String stackName
	Map<String, String> stackParams = [:]
	String templateURL
	
	File templateFile
	String templateBucket
	String templateKeyPrefix
	
	boolean capabilityIam
	
	AwsCloudFormationPluginExtension(Project project) {
		this.project = project;
	}

	def stackParams(Map<String, String> stackParams) {
		this.stackParams = stackParams
	}
	
	Stack getStack(String stackName) {
		return cfn.describeStacks(new DescribeStacksRequest().withStackName(stackName)).stacks[0]
	}
	
	List<StackResource> getStackResources(String stackName) {
		return cfn.describeStackResources(new DescribeStackResourcesRequest().withStackName(stackName)).stackResources
	}
}
