# Amazon Simple Queue Service [SQS]

## Overview

[AWS SQS Documentation](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/welcome.html)

[PRICING](https://aws.amazon.com/sqs/pricing/)

[AWS SQS FAQ](https://aws.amazon.com/sqs/faqs/#:~:text=A%20single%20Amazon%20SQS%20message,20%2C000%20for%20a%20FIFO%20queue.)

Available containerized SQS implementations
- [localstack](https://hub.docker.com/r/localstack/localstack)
- [ElasticMQ](https://github.com/softwaremill/elasticmq?tab=readme-ov-file#summary) (no access to cloudwatch statistics.)

Current R&D [see PR](https://github.com/WorkerFramework/worker-framework/pull/198) is done using localstack community edition.

- [SQSTaskInformation](#sqstaskinformation)
- [Queue Limitations](#queue-limitations)
- [Configuration](#configuration)
- [Required Worker Actions](#required-worker-actions)
- [Failures](#failure-handling)
- [Poison Messages](#poison-messages)
- [Extending Visibility Timeout](#extending-visibility-timeout)
- [Metrics (for autoscaling)](#metrics)
- [Tenant Specific Queues (message prioritization)](#tenant-specific-queues)
- [Questions](#questions)


## SQSTaskInformation

The SQSTaskInformation class extends the TaskInformation and contains:
- message id
- queue info(name, url, arn)
- receipt handle
- visibility timeout expiration
- flag to indicate if is a poison message (i.e. read from DLQ)

This object should be passed on all API calls requiring action.

## Queue Limitations

- Unlimited number of visible, but not yet delivered, messages.
- Max number of in-flight messages (delivered but not yet deleted) 120,000.
- Max number of messages published at a time 10.
- Max number of messages delivered at a time 10.
- Max message size 256Kb, this also applies to total batch size.

## Configuration

The following cfg concepts have been tested against a localstack container running sqs.

- Visibility timeout  
  The plan here is to keep the visibility time relatively short, and keep extending the timeout for individual
  inflight messages.  This short timeout ensures that if the worker crashes messages will be redelivered
  on startup.

- Long polling interval   
  How long we wait for message to appear on the queue. Too short, and we have unnecessary requests made when the queue
  is empty.

- Max deliveries of a message   
  How many times we expect to try a redelivery of an unack'd message.

- Message Retention  
  How long a message is allowed to remain on a queue before it is deleted automatically.

## Required Worker Actions
The worker MUST take the following actions:
- Acknowledge when processing completes successfully.

- Make an API call when processing an individual message fails and a redelivery is required.

- Make an API call when processing an individual message fails and the message should be removed from the
input queue and sent to another(retry?) queue.

## Failure Handling

In the context of SQS a failure is a message that either:
- has not been ack'd(deleted) && has been delivered the max number of times.

- has exceeded the max retention period set for a queue.

Failed messages will be been moved to the dead letter queue.

## Poison Messages
Messages are consumed from the dead letter queue and marked as a poison message.  

This attempts to replicate current RabbitMQ consumer behaviour which marks
messages as poison.

SQS flow marks the message as a poison message, and it is deleted from the dlq when ack'd.

## Extending Visibility Timeout
We want the facility to request more time to complete processing a particular message.

The VisibilityMonitor watches a sorted set of VisibilityTimeout objects, derived from the 
SQSTaskInformation. The receipt handle on the VisibilityTimeout maps directly to one and only one message on the
queue.

The sorted set is queried at half the queue visibility timeout period, and any messages becoming visible in the next
sliding window period (equal to the visibility timeout period * 2) are extended.

For Example, given a timout of 60 seconds and a sliding window period of 60 seconds.
```
Process starts|---visibility timeout 60 seconds------->|---------visibility timeout 60 seconds------->|
At 30 seconds |---no timeouts till 60 seconds--------->|
First check   |---at half period-->|<-extend--anything timing out before here-->|----to timout here-->|
Result        |----no timeouts till 60 seconds------------------------------------------------------->|
Next check    |--------------------|--at half period-->|<----extend-anything timing out here--------->|--etc...
```

As such we constantly push the approaching timeouts forward by the configured sliding window period.

By increasing the timeout as the current timeout approaches a message on the queue will only be redelivered if it is 
removed from the VisibilityMonitor.  

A message will be removed from the VisibilityMonitor if:
- an error in processing occurs.
- an error extending visibility occurs.
- processing completes successfully.

## Metrics

### States that indicate more workers/consumers are required
- The number of in flight messages approaches the max (120,000)
- The rate messages are sent to SQS is higher than

### SQS Derived Metrics
#### Queue capacity attributes
- ApproximateNumberOfMessages – Returns the approximate number of messages available for retrieval from the queue.
- ApproximateNumberOfMessagesDelayed – Returns the approximate number of messages in the queue that are delayed and
  not available for reading immediately. This can happen when the queue is configured as a delay queue or when a message has been sent with a delay parameter.
- ApproximateNumberOfMessagesNotVisible – Returns the approximate number of messages that are in flight.
  Messages are considered to be in flight if they have been sent to a client but have not yet been deleted or
  have not yet reached the end of their visibility window.

```
The ApproximateNumberOfMessagesDelayed, ApproximateNumberOfMessagesNotVisible, and ApproximateNumberOfMessages metrics 
may not achieve consistency until at least 1 minute after the producers stop sending 
messages. This period is required for the queue metadata to reach eventual consistency.
```

### CloudWatch Derived Metrics
Amazon Cloud watch provides metrics via the cloudwatch API.  
[Available Metrics](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-available-cloudwatch-metrics.html)

Using the localstack container we have been able to load a subset of the metrics defined for AWS:
- NumberOfMessagesSent (received by SQS)
- NumberOfMessagesReceived (delivered by SQS, includes redeliveries)
- NumberOfMessagesDeleted
- ApproximateNumberOfMessagesVisible
- ApproximateNumberOfMessagesNotVisible

Metrics that would be useful but unavailable using localstack is:
- ApproximateAgeOfOldestMessage

Metrics can be reported in periods of multiple of 60 seconds, with start and end times.

The `NumberOfMessagesSent` metric seems to be reliable, but it should be noted that there appears to be some
inconsistencies between `NumberOfMessagesSent` and `ApproximateNumberOfMessagesVisible`, though this may be limited
to the localstack implementation.

![Image showing inconsistencies](inconsistent_metrics.png)

## Tenant Specific Queues

While not used in this project this class is designed flesh out how to stop a particular tenant from effectively 
blocking another tenant's messages by interleaving messages from different tenant specific queues into a single 
worker input queue.

![Tenant blocked queue](msg_prioritization.png)

This is not natively supported in SQS, but we have created a MessageDistributor class that moves a given number 
of messages from one queue to another.

SQS does require that we read from a source, write to a destination, then delete the moved messages from the source 
queue.

## Questions
When a worker receives a poison message, it does not process it, but does it ack that message. (yes) 
What is managing the retry queue.  
Some confusion over InavlidTaskException/TaskRejectedException handling. (cleared) 
Whats the magnitude of inflight message we expect a worker to be handling. (will vary from x10 t0 x1000) 
What is max tasks indicating, seems to just limit number of messages read at a time. (max tasks worker can handle) 
Whats lastmessage indicating. (publish==ack'd && isLastMessage means it can be deleted) 
Who's responsibility is it to create queue structures, will worker A expect a downstream worker B to have created the 
downstream queue, if publish is called and B's queue does not exist does worker A create it, or throw an error? (on publish isf queue not exists then create) 
Is the paused queue obsolete. (no)
Util Module required?  
What are internal metrics used for.  
What cfg do we want to expose.(everthing as is at present)  




