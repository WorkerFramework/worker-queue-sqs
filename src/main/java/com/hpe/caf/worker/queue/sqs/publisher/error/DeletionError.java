/*
 * Copyright 2015-2024 Open Text.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hpe.caf.worker.queue.sqs.publisher.error;

import com.hpe.caf.worker.queue.sqs.publisher.message.DeleteMessage;

public record DeletionError(String error, DeleteMessage deleteMessage)
{
    @Override
    public String toString()
    {
        final var taskInfo = deleteMessage.getSqsTaskInformation();
        return String.format("Failed deleting task from queue %s.\n%s: %s",
                taskInfo.getQueueInfo().name(),
                error,
                taskInfo.getReceiptHandle());
    }
}
