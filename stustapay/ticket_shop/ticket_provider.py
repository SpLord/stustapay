import enum
import logging
from abc import abstractmethod
from datetime import datetime

import asyncpg
from pydantic import BaseModel
from sftkit.database import Connection

from stustapay.core.config import Config
from stustapay.core.schema.tree import Node

logger = logging.getLogger(__name__)


class ExternalTicketType(enum.Enum):
    pretix = "pretix"


class CreateExternalTicket(BaseModel):
    external_reference: str
    created_at: datetime
    token: str
    ticket_type: ExternalTicketType
    external_link: str | None
    customer_email: str | None
    customer_name: str | None
    initial_top_up_amount: float
    pretix_item_id: int | None
    pretix_product_name: str | None


class ExternalTicket(CreateExternalTicket):
    id: int
    customer_account_id: int
    has_checked_in: bool
    cancelled: bool
    externally_checked_in: bool


class PresaleStats(BaseModel):
    total_tickets: int
    checked_in_tickets: int
    cancelled_tickets: int
    total_credit_sold: float
    credit_activated: float
    credit_pending: float


async def fetch_external_tickets(conn: Connection, node: Node) -> list[ExternalTicket]:
    return await conn.fetch_many(
        ExternalTicket,
        "select "
        "   tv.*, "
        "   a.name as customer_name, "
        "   ci.email as customer_email, "
        "   case when a.user_tag_id is null then false else true end as has_checked_in "
        "from ticket_voucher tv "
        "join account a on tv.customer_account_id = a.id "
        "left join customer_info ci on ci.customer_account_id = a.id "
        "where tv.node_id = $1",
        node.event_node_id,
    )


class TicketProvider:
    def __init__(self, config: Config, db_pool: asyncpg.Pool):
        self.config = config
        self.db_pool = db_pool

    @abstractmethod
    async def synchronize_tickets(self):
        pass

    async def store_external_ticket(self, conn: Connection, node: Node, ticket: CreateExternalTicket) -> bool:
        """
        Insert a new external ticket voucher or update an existing one (matched by token).
        Returns True if a new voucher was created.
        """
        existing = await conn.fetchrow(
            "select tv.id, tv.customer_account_id, tv.initial_top_up_amount, a.user_tag_id "
            "from ticket_voucher tv "
            "join account a on tv.customer_account_id = a.id "
            "where tv.node_id = $1 and tv.token = $2",
            node.event_node_id,
            ticket.token,
        )
        if existing is None:
            customer_account_id = await conn.fetchval(
                "insert into account(node_id, type, name) values ($1, 'private', $2) returning id",
                node.event_node_id,
                ticket.customer_name,
            )
            await conn.execute(
                "insert into ticket_voucher(node_id, created_at, customer_account_id, token, ticket_type, "
                "   external_link, external_reference, initial_top_up_amount, pretix_item_id, pretix_product_name) "
                "   values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)",
                node.event_node_id,
                ticket.created_at,
                customer_account_id,
                ticket.token,
                ticket.ticket_type.name,
                ticket.external_link,
                ticket.external_reference,
                ticket.initial_top_up_amount,
                ticket.pretix_item_id,
                ticket.pretix_product_name,
            )
            if ticket.customer_email:
                await conn.execute(
                    "insert into customer_info (customer_account_id, email) values ($1, $2) "
                    "on conflict (customer_account_id) do update set email = $2",
                    customer_account_id,
                    ticket.customer_email,
                )
            return True
        else:
            # Update existing voucher with latest data from Pretix.
            # All updates are guarded with "is distinct from" so unchanged data does not produce a write.
            voucher_id = existing["id"]
            customer_account_id = existing["customer_account_id"]
            has_checked_in = existing["user_tag_id"] is not None

            await conn.execute(
                "update ticket_voucher set pretix_item_id = $1, pretix_product_name = $2 "
                "where id = $3 and (pretix_item_id, pretix_product_name) is distinct from ($1, $2)",
                ticket.pretix_item_id,
                ticket.pretix_product_name,
                voucher_id,
            )

            if has_checked_in:
                # Once the ticket is checked in the top-up has been booked and the account belongs to a real
                # customer, so the amount, name and email must not be silently changed anymore.
                existing_top_up = float(existing["initial_top_up_amount"])
                if abs(existing_top_up - ticket.initial_top_up_amount) > 1e-9:
                    logger.warning(
                        f"Top-up amount for already checked-in ticket {ticket.token[:8]}... "
                        f"(order {ticket.external_reference}) changed in the ticket shop from "
                        f"{existing_top_up:.2f} to {ticket.initial_top_up_amount:.2f}; not updating"
                    )
                return False

            await conn.execute(
                "update ticket_voucher set initial_top_up_amount = $1 "
                "where id = $2 and initial_top_up_amount is distinct from $1",
                ticket.initial_top_up_amount,
                voucher_id,
            )
            if ticket.customer_name:
                await conn.execute(
                    "update account set name = $1 where id = $2 and name is distinct from $1",
                    ticket.customer_name,
                    customer_account_id,
                )
            if ticket.customer_email:
                await conn.execute(
                    "insert into customer_info (customer_account_id, email) values ($1, $2) "
                    "on conflict (customer_account_id) do update set email = excluded.email "
                    "where customer_info.email is distinct from excluded.email",
                    customer_account_id,
                    ticket.customer_email,
                )
            return False
